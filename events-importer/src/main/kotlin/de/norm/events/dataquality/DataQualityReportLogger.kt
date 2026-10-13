package de.norm.events.dataquality

import de.norm.events.importing.EventSourceRepository
import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.LogContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Writes the day's snapshot, refreshes the gauges, and logs the summary — once a day.
 *
 * The report endpoint is what somebody reads; this is what makes the numbers survive being read.
 * Without a persisted series, Pillars 3 and 4 have no "before" to move, which is the ordering
 * argument the whole strategy rests on.
 *
 * `@ConditionalOnProperty` on `app.scheduling.enabled` follows `ScheduledImportService`, so the test
 * suite does not race a background write against its own assertions. The property also gates
 * [de.norm.events.SchedulingConfiguration], which is what stops the untagged `@Scheduled` beans (#949).
 *
 * At start it also republishes the regression gauge from today's stored rows (#3223). The gauge
 * lives in the process, so without this a restart after 03:00 leaves the alert without data
 * until the next night.
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class DataQualityReportLogger(
    private val service: DataQualityService,
    private val snapshots: DataQualitySnapshotRepository,
    private val metrics: DataQualityMetrics,
    private val eventSources: EventSourceRepository,
    private val clock: Clock = Clock.systemUTC()
) : ApplicationRunner {
    private val logger = KotlinLogging.logger {}

    /** `runBlocking` is fine here: the runner executes on the main thread, not an event loop. */
    override fun run(args: ApplicationArguments) {
        runBlocking { publishStoredRegressions() }
    }

    /**
     * Judges today's stored rows again and sets `data_quality_regression`, or does nothing when the
     * 03:00 run has not written today's rows yet (#3223).
     *
     * Gauge only, no per-verdict lines: the 03:00 run logged those already, and a rollout must not
     * repeat them. Failures are caught for the reason [snapshot] gives: a start must not fail on a
     * measurement.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: measurement must not be able to fail the application
    suspend fun publishStoredRegressions() {
        try {
            val today = LocalDate.now(clock)
            val rows = snapshots.findBySnapshotDate(today).toList()
            if (rows.isEmpty()) {
                logger.info { "No data-quality snapshot for $today yet; the regression gauge waits for the nightly run" }
                return
            }
            val verdicts = judge(today, rows)
            logger.info {
                "Republished the data-quality regression gauge from $today's snapshot: " +
                    "${verdicts.size} series, ${verdicts.count { it.regressed }} regressed"
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not republish the data-quality regression gauge at start; it stays empty until the nightly run" }
        }
    }

    /**
     * `cron` rather than `fixedDelay`, because a *daily* snapshot has to land on a day boundary
     * rather than 24 hours after whenever the process last started — otherwise a restart shifts the
     * series and two rows can fall on one date while another day gets none.
     *
     * 03:00 UTC: after the 02:30 base backup and comfortably after the nightly import window, so it
     * measures a settled corpus rather than one mid-write.
     *
     * **Failures are caught, not propagated**, for the same reason `MetricsRefreshService` catches
     * its own: this shares the scheduler that runs the imports, and a measurement job able to kill
     * it would make observability a source of outages. A missing snapshot row is itself detectable
     * — a gap in the series — and strictly better than a dead importer.
     */
    @Scheduled(cron = $$"${app.data-quality.snapshot-cron:0 0 3 * * *}")
    @Suppress("TooGenericExceptionCaught") // Intentional: measurement must not be able to fail the application
    suspend fun snapshot() {
        try {
            val report = service.report()
            val today = LocalDate.now(clock)

            // Read first, so a second run on the same day updates rather than colliding with the
            // (snapshot_date, source_slug, metric) unique constraint. The job must be safe to
            // trigger by hand — that is how a missing day gets backfilled.
            val existing =
                snapshots
                    .findBySnapshotDate(today)
                    .toList()
                    .associateBy { it.sourceSlug to it.metric }

            val rows =
                report.perSource.flatMap { source ->
                    measurements(source).map { (metric, count) ->
                        val key = source.source to metric
                        metrics.publish(source.source, metric, count)
                        DataQualitySnapshotEntity(
                            id = existing[key]?.id,
                            snapshotDate = today,
                            sourceSlug = source.source,
                            metric = metric,
                            metricCount = count,
                            totalEvents = source.totalEvents
                        )
                    }
                }
            snapshots.saveAll(rows).toList()

            // One line, not one per metric: a daily summary somebody actually reads in an aggregated
            // log, with the headline number first because it is the one that moves the product.
            val overall = report.overall
            logger.info {
                "Data quality: ${overall.totalEvents} events, " +
                    "${overall.concertsWithoutArtist} concerts without an artist (${overall.concertsWithoutArtistPct}%), " +
                    "${overall.eventsTypedOther} typed OTHER, ${overall.missingGenre} without genre, " +
                    "${overall.missingPromoter} without promoter, ${overall.missingPrice} without price, " +
                    "${overall.missingStartTime} without start time, " +
                    "${overall.suspectNonArtistTitles} suspect artist names, " +
                    "${overall.titleDerivedSingletons} title-named one-event headliners " +
                    "(${overall.titleDerivedUnmatched} unknown to MusicBrainz), " +
                    "${overall.flaggedAtImport} flagged at import, " +
                    "${overall.unreviewedLicence} from unreviewed sources (${overall.unreviewedLicencePct}%) — " +
                    "${rows.size} snapshot rows for $today across ${report.perSource.size} source(s)"
            }

            checkForRegressions(today, rows)
        } catch (e: Exception) {
            logger.warn(e) { "Could not write or check the data-quality snapshot; the series may have a gap for today" }
        }
    }

    /**
     * The Pillar 2 gate (#2604): today's shares against each source's own 7-day median.
     *
     * Runs after the write and the summary line, so a failure here still leaves today's rows in the
     * table, and inside the same catch, so it cannot stop the scheduler either. One WARN per regressed source and metric:
     * the alert says that something regressed, and this line says which source, which metric and by
     * how much. A share that got worse on a declared limitation gets an INFO line and no alert (#3195).
     */
    private suspend fun checkForRegressions(
        today: LocalDate,
        rows: List<DataQualitySnapshotEntity>
    ) {
        judge(today, rows).filter { it.worse }.forEach { verdict ->
            val numbers =
                "${verdict.metric} of ${verdict.source} is at ${pct(verdict.todayPct)}% " +
                    "against a 7-day median of ${pct(verdict.baselinePct ?: 0.0)}% (${verdict.totalEvents} events)"
            logger.at(if (verdict.regressed) Level.WARN else Level.INFO) {
                message =
                    if (verdict.regressed) {
                        "Data quality regressed: $numbers"
                    } else {
                        "Data quality got worse on an accepted limitation, not a regression: $numbers"
                    }
                payload = mapOf(LogContext.SOURCE_SLUG to verdict.source)
            }
        }
    }

    /** Judges [rows] against the week before [today], publishes the verdicts as the gauge, and returns them. */
    private suspend fun judge(
        today: LocalDate,
        rows: List<DataQualitySnapshotEntity>
    ): List<DataQualityRegressionCheck.Verdict> {
        val history =
            snapshots
                .findBySnapshotDateBetween(DataQualityRegressionCheck.baselineStart(today), today.minusDays(1))
                .toList()
        val verdicts = DataQualityRegressionCheck.check(rows, history, declaredAspectsBySlug())
        metrics.publishRegressions(verdicts)
        return verdicts
    }

    /** The aspects each source's `VenueLimitations` declare, keyed by slug, for the gate to skip (#3195). */
    private suspend fun declaredAspectsBySlug(): Map<String, Set<LimitedAspect>> {
        val sourcesByName = EventSource.entries.associateBy { it.name }
        return eventSources.findAll().toList().associate { source ->
            source.slug to
                sourcesByName[source.sourceType]
                    ?.let { type -> AcceptedLimitations.forSource(type).map { it.aspect }.toSet() }
                    .orEmpty()
        }
    }

    private fun pct(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)

    /**
     * The metrics worth persisting, per source.
     *
     * `totalEvents` is stored as a metric row of its own **as well as** on every other row's
     * denominator. That redundancy is deliberate: a chart of "how many events does this source
     * have" is a question people ask, and making it derivable only from another metric's row means
     * losing it the day that metric is retired.
     *
     * `suspectNonArtistTitles` is included even though it counts names rather than events, with the
     * event total as its denominator, because the alternative — leaving it out of history — makes
     * the one metric about *accuracy* the only one nobody can chart.
     */
    private fun measurements(source: SourceQualityMetrics): List<Pair<String, Long>> =
        listOf(
            DataQualityMetrics.TOTAL_EVENTS to source.totalEvents,
            QualityIssue.CONCERTS_WITHOUT_ARTIST.key to source.concertsWithoutArtist,
            QualityIssue.EVENTS_TYPED_OTHER.key to source.eventsTypedOther,
            QualityIssue.MISSING_GENRE.key to source.missingGenre,
            QualityIssue.MISSING_PROMOTER.key to source.missingPromoter,
            QualityIssue.MISSING_PRICE.key to source.missingPrice,
            QualityIssue.MISSING_START_TIME.key to source.missingStartTime,
            QualityIssue.UNREVIEWED_LICENCE.key to source.unreviewedLicence,
            QualityIssue.TITLE_DERIVED_SINGLETONS.key to source.titleDerivedSingletons,
            QualityIssue.TITLE_DERIVED_UNMATCHED.key to source.titleDerivedUnmatched,
            QualityIssue.FLAGGED_AT_IMPORT.key to source.flaggedAtImport,
            DataQualityRegressionCheck.FLAGGED_AT_IMPORT_KEPT_OUT to source.flaggedAtImportKeptOut,
            SUSPECT_NON_ARTIST_TITLES to source.suspectNonArtistTitles
        )

    private companion object {
        const val SUSPECT_NON_ARTIST_TITLES = "suspectNonArtistTitles"
    }
}

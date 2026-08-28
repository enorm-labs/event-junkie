package de.norm.events.dataquality

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
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
 * suite does not race a background write against its own assertions.
 */
@Service
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class DataQualityReportLogger(
    private val service: DataQualityService,
    private val snapshots: DataQualitySnapshotRepository,
    private val metrics: DataQualityMetrics,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

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
                    "${overall.unreviewedLicence} from unreviewed sources (${overall.unreviewedLicencePct}%) — " +
                    "${rows.size} snapshot rows for $today across ${report.perSource.size} source(s)"
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not write the data-quality snapshot; the series will have a gap for today" }
        }
    }

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
            SUSPECT_NON_ARTIST_TITLES to source.suspectNonArtistTitles
        )

    private companion object {
        const val SUSPECT_NON_ARTIST_TITLES = "suspectNonArtistTitles"
    }
}

package de.norm.events.dataquality

import de.norm.events.importing.EventQualityFlagRepository
import de.norm.events.importing.EventSourceEntity
import de.norm.events.importing.EventSourceRepository
import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.isNonArtistName
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.math.round

/**
 * Turns the two aggregate queries into the report, and the roll-up math is the part worth reading.
 *
 * **`overall` is summed from the per-source rows rather than queried again**, and that is a
 * correctness decision rather than an optimisation. Two queries taken moments apart against a table
 * an importer is writing to would disagree, and the disagreement would be small, intermittent, and
 * exactly the kind of thing that makes somebody stop trusting a dashboard.
 */
@Service
class DataQualityService(
    private val repository: DataQualityRepository,
    private val worklistRepository: DataQualityWorklistRepository,
    private val eventSourceRepository: EventSourceRepository,
    private val qualityFlagRepository: EventQualityFlagRepository,
    /** Injected for deterministic time in tests, as elsewhere in this module. */
    private val clock: Clock = Clock.systemUTC()
) {
    /** The full report: every source, plus the roll-up. */
    suspend fun report(): DataQualityReportResponse {
        val rows = repository.aggregatePerSource().toList()
        val sourcesById = sourcesById()
        val slugsById = sourcesById.mapValues { it.value.slug }
        val suspectByeSource = suspectNonArtistNamesPerSource()

        val perSource =
            rows
                .map { row ->
                    metrics(
                        label(row.eventSourceId, slugsById),
                        row,
                        suspectByeSource[row.eventSourceId] ?: 0L,
                        excusedIssues(row.eventSourceId?.let(sourcesById::get))
                    )
                }.sortedBy { it.source }

        return DataQualityReportResponse(
            generatedAt = Instant.now(clock),
            overall = rollUp(perSource),
            perSource = perSource
        )
    }

    /**
     * Per source label, the events dated in `[from, until)` and how many of them state no price
     * (#4420). The regression gate reads this; the report keeps the count over every event.
     */
    suspend fun upcomingPriceGaps(
        from: LocalDate,
        until: LocalDate
    ): Map<String, UpcomingPriceRow> {
        val slugsById = sourceSlugsById()
        return repository.upcomingPricePerSource(from, until).toList().associateBy { label(it.eventSourceId, slugsById) }
    }

    /** A page of the events failing one metric. */
    suspend fun worklist(
        issue: QualityIssue,
        source: String?,
        limit: Int,
        offset: Int
    ): WorklistResponse {
        val rows = worklistRepository.findOffenders(issue, source, limit, offset).toList()
        val flagsByEventId = qualityFlagRepository.findByEventIds(rows.map { it.id }).groupBy { it.eventId }
        val entries = rows.map { WorklistEntryResponse.fromRow(it, flagsByEventId[it.id].orEmpty()) }
        return WorklistResponse(
            issue = issue.key,
            source = source,
            count = entries.size,
            dismissed = worklistRepository.countDismissed(issue, source),
            entries = entries
        )
    }

    /**
     * What to call a row.
     *
     * `null` is the `manual` bucket — an event created through the admin API, which genuinely has
     * no source. An id that does not resolve is something else entirely and gets a label that says
     * so: `event.event_source_id` is `ON DELETE SET NULL`, so it should not happen, but "should not
     * happen" is how rows go missing from a report silently. **Folding it into `manual` would be
     * worse than dropping it**, because it would attribute a deleted source's events to hand
     * curation and nobody would ever question the number.
     */
    private fun label(
        sourceId: Long?,
        slugsById: Map<Long, String>
    ): String =
        when {
            sourceId == null -> DataQualityWorklistRepository.MANUAL_BUCKET
            else -> slugsById[sourceId] ?: "$UNRESOLVED_PREFIX$sourceId"
        }

    private suspend fun sourceSlugsById(): Map<Long, String> = sourcesById().mapValues { it.value.slug }

    private suspend fun sourcesById(): Map<Long, EventSourceEntity> =
        eventSourceRepository
            .findAll()
            .toList()
            .mapNotNull { source -> source.id?.let { it to source } }
            .toMap()

    /**
     * The metrics [source]'s accepted limitations excuse (#5002), by the rule the regression gate
     * uses. `manual` and an unresolved source have no source type, so nothing excuses their events.
     */
    private fun excusedIssues(source: EventSourceEntity?): Set<QualityIssue> =
        source
            ?.let { DataQualityRegressionCheck.excusedIssues(AcceptedLimitations.declaredAspects(it.sourceType)) }
            .orEmpty()
            .toSet()

    /**
     * The one metric SQL cannot express, because `isNonArtistName` is a curated Kotlin vocabulary.
     *
     * Counts **distinct names per source**, which the query already guarantees — a placeholder that
     * appears on forty events is one name to fix.
     */
    private suspend fun suspectNonArtistNamesPerSource(): Map<Long?, Long> =
        repository
            .artistNamesPerSource()
            .toList()
            .filter { isNonArtistName(it.artistName) }
            .groupingBy { it.eventSourceId }
            .eachCount()
            .mapValues { (_, count) -> count.toLong() }

    private fun metrics(
        slug: String,
        row: SourceQualityRow,
        suspectNames: Long,
        excused: Set<QualityIssue>
    ): SourceQualityMetrics {
        fun unexplained(
            issue: QualityIssue,
            count: Long
        ): Long = if (issue in excused) 0L else count

        val concerts = unexplained(QualityIssue.CONCERTS_WITHOUT_ARTIST, row.concertsWithoutArtist)
        val other = unexplained(QualityIssue.EVENTS_TYPED_OTHER, row.eventsTypedOther)
        val genre = unexplained(QualityIssue.MISSING_GENRE, row.missingGenre)
        val promoter = unexplained(QualityIssue.MISSING_PROMOTER, row.missingPromoter)
        val price = unexplained(QualityIssue.MISSING_PRICE, row.missingPrice)
        val startTime = unexplained(QualityIssue.MISSING_START_TIME, row.missingStartTime)
        return SourceQualityMetrics(
            source = slug,
            totalEvents = row.totalEvents,
            concertsWithoutArtist = row.concertsWithoutArtist,
            concertsWithoutArtistPct = pct(row.concertsWithoutArtist, row.totalEvents),
            eventsTypedOther = row.eventsTypedOther,
            eventsTypedOtherPct = pct(row.eventsTypedOther, row.totalEvents),
            missingGenre = row.missingGenre,
            missingGenrePct = pct(row.missingGenre, row.totalEvents),
            missingPromoter = row.missingPromoter,
            missingPromoterPct = pct(row.missingPromoter, row.totalEvents),
            missingPrice = row.missingPrice,
            missingPricePct = pct(row.missingPrice, row.totalEvents),
            missingStartTime = row.missingStartTime,
            missingStartTimePct = pct(row.missingStartTime, row.totalEvents),
            suspectNonArtistTitles = suspectNames,
            unreviewedLicence = row.unreviewedLicence,
            unreviewedLicencePct = pct(row.unreviewedLicence, row.totalEvents),
            titleDerivedSingletons = row.titleDerivedSingletons,
            titleDerivedUnmatched = row.titleDerivedUnmatched,
            flaggedAtImport = row.flaggedAtImport,
            flaggedAtImportKeptOut = row.flaggedAtImportKeptOut,
            limitationAccepted =
                DataQualityRegressionCheck.ACCEPTED_ASPECT.keys
                    .filter { it in excused }
                    .map { it.key },
            concertsWithoutArtistUnexplained = concerts,
            concertsWithoutArtistUnexplainedPct = pct(concerts, row.totalEvents),
            eventsTypedOtherUnexplained = other,
            eventsTypedOtherUnexplainedPct = pct(other, row.totalEvents),
            missingGenreUnexplained = genre,
            missingGenreUnexplainedPct = pct(genre, row.totalEvents),
            missingPromoterUnexplained = promoter,
            missingPromoterUnexplainedPct = pct(promoter, row.totalEvents),
            missingPriceUnexplained = price,
            missingPriceUnexplainedPct = pct(price, row.totalEvents),
            missingStartTimeUnexplained = startTime,
            missingStartTimeUnexplainedPct = pct(startTime, row.totalEvents)
        )
    }

    private fun rollUp(perSource: List<SourceQualityMetrics>): SourceQualityMetrics {
        val total = perSource.sumOf { it.totalEvents }
        val concerts = perSource.sumOf { it.concertsWithoutArtist }
        val other = perSource.sumOf { it.eventsTypedOther }
        val genre = perSource.sumOf { it.missingGenre }
        val promoter = perSource.sumOf { it.missingPromoter }
        val price = perSource.sumOf { it.missingPrice }
        val startTime = perSource.sumOf { it.missingStartTime }
        val unreviewed = perSource.sumOf { it.unreviewedLicence }
        val concertsUnexplained = perSource.sumOf { it.concertsWithoutArtistUnexplained }
        val otherUnexplained = perSource.sumOf { it.eventsTypedOtherUnexplained }
        val genreUnexplained = perSource.sumOf { it.missingGenreUnexplained }
        val promoterUnexplained = perSource.sumOf { it.missingPromoterUnexplained }
        val priceUnexplained = perSource.sumOf { it.missingPriceUnexplained }
        val startTimeUnexplained = perSource.sumOf { it.missingStartTimeUnexplained }
        return SourceQualityMetrics(
            source = OVERALL,
            totalEvents = total,
            concertsWithoutArtist = concerts,
            concertsWithoutArtistPct = pct(concerts, total),
            eventsTypedOther = other,
            eventsTypedOtherPct = pct(other, total),
            missingGenre = genre,
            missingGenrePct = pct(genre, total),
            missingPromoter = promoter,
            missingPromoterPct = pct(promoter, total),
            missingPrice = price,
            missingPricePct = pct(price, total),
            missingStartTime = startTime,
            missingStartTimePct = pct(startTime, total),
            // Summed rather than re-derived: the same name on two sources is two names to fix, one
            // per source, and there is no cheaper truth available from the per-source counts alone.
            suspectNonArtistTitles = perSource.sumOf { it.suspectNonArtistTitles },
            unreviewedLicence = unreviewed,
            unreviewedLicencePct = pct(unreviewed, total),
            titleDerivedSingletons = perSource.sumOf { it.titleDerivedSingletons },
            titleDerivedUnmatched = perSource.sumOf { it.titleDerivedUnmatched },
            flaggedAtImport = perSource.sumOf { it.flaggedAtImport },
            flaggedAtImportKeptOut = perSource.sumOf { it.flaggedAtImportKeptOut },
            // The roll-up excuses nothing itself: each source's own excuses are in its unexplained counts.
            limitationAccepted = emptyList(),
            concertsWithoutArtistUnexplained = concertsUnexplained,
            concertsWithoutArtistUnexplainedPct = pct(concertsUnexplained, total),
            eventsTypedOtherUnexplained = otherUnexplained,
            eventsTypedOtherUnexplainedPct = pct(otherUnexplained, total),
            missingGenreUnexplained = genreUnexplained,
            missingGenreUnexplainedPct = pct(genreUnexplained, total),
            missingPromoterUnexplained = promoterUnexplained,
            missingPromoterUnexplainedPct = pct(promoterUnexplained, total),
            missingPriceUnexplained = priceUnexplained,
            missingPriceUnexplainedPct = pct(priceUnexplained, total),
            missingStartTimeUnexplained = startTimeUnexplained,
            missingStartTimeUnexplainedPct = pct(startTimeUnexplained, total)
        )
    }

    /**
     * A percentage to one decimal, and **zero when there is nothing to divide by** rather than NaN.
     *
     * An empty source is a real state — a newly added one that has not run yet — and `NaN`
     * serialises to JSON as something no client parses the same way.
     */
    private fun pct(
        count: Long,
        total: Long
    ): Double = if (total == 0L) 0.0 else round(count * PERCENT * DECIMAL / total) / DECIMAL

    companion object {
        /** The roll-up's label in the response, and never a real source slug. */
        const val OVERALL = "overall"

        /**
         * Prefix for a source id that no longer resolves to a row. Deliberately not a slug shape, so
         * it cannot be mistaken for one in a dashboard's legend.
         */
        private const val UNRESOLVED_PREFIX = "unresolved-source-"
        private const val PERCENT = 100.0
        private const val DECIMAL = 10.0
    }
}

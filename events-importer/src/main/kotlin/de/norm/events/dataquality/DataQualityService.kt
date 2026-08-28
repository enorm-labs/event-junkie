package de.norm.events.dataquality

import de.norm.events.scraper.EventSourceRepository
import de.norm.events.scraper.isNonArtistName
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
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
    /** Injected for deterministic time in tests, as elsewhere in this module. */
    private val clock: Clock = Clock.systemUTC()
) {
    /** The full report: every source, plus the roll-up. */
    suspend fun report(): DataQualityReportResponse {
        val rows = repository.aggregatePerSource().toList()
        val slugsById = sourceSlugsById()
        val suspectByeSource = suspectNonArtistNamesPerSource()

        val perSource =
            rows
                .map { row ->
                    metrics(label(row.eventSourceId, slugsById), row, suspectByeSource[row.eventSourceId] ?: 0L)
                }.sortedBy { it.source }

        return DataQualityReportResponse(
            generatedAt = Instant.now(clock),
            overall = rollUp(perSource),
            perSource = perSource
        )
    }

    /** A page of the events failing one metric. */
    suspend fun worklist(
        issue: QualityIssue,
        source: String?,
        limit: Int,
        offset: Int
    ): WorklistResponse {
        val entries =
            worklistRepository
                .findOffenders(issue, source, limit, offset)
                .map(WorklistEntryResponse::fromRow)
                .toList()
        return WorklistResponse(issue = issue.key, source = source, count = entries.size, entries = entries)
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

    private suspend fun sourceSlugsById(): Map<Long, String> =
        eventSourceRepository
            .findAll()
            .toList()
            .mapNotNull { source -> source.id?.let { it to source.slug } }
            .toMap()

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
        suspectNames: Long
    ) = SourceQualityMetrics(
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
        unreviewedLicencePct = pct(row.unreviewedLicence, row.totalEvents)
    )

    private fun rollUp(perSource: List<SourceQualityMetrics>): SourceQualityMetrics {
        val total = perSource.sumOf { it.totalEvents }
        val concerts = perSource.sumOf { it.concertsWithoutArtist }
        val other = perSource.sumOf { it.eventsTypedOther }
        val genre = perSource.sumOf { it.missingGenre }
        val promoter = perSource.sumOf { it.missingPromoter }
        val price = perSource.sumOf { it.missingPrice }
        val startTime = perSource.sumOf { it.missingStartTime }
        val unreviewed = perSource.sumOf { it.unreviewedLicence }
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
            unreviewedLicencePct = pct(unreviewed, total)
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
        const val UNRESOLVED_PREFIX = "unresolved-source-"
        private const val PERCENT = 100.0
        private const val DECIMAL = 10.0
    }
}

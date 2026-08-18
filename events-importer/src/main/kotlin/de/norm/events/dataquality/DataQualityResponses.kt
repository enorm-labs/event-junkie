package de.norm.events.dataquality

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The report payload.
 *
 * Every metric carries **both a count and a percentage**, and neither is redundant: the percentage
 * is what a human scans down a column, and the count is what a test asserts and what a trend needs.
 * A percentage alone hides the denominator, and 40% of five events is a different finding from 40%
 * of five hundred.
 */
@Schema(description = "Per-source data-quality metrics, with an overall roll-up")
data class DataQualityReportResponse(
    @Schema(description = "When this report was computed. It is live, not cached — the numbers are as of this instant")
    val generatedAt: Instant,
    @Schema(description = "Every source's metrics summed. Not a second query: computed from the rows below")
    val overall: SourceQualityMetrics,
    @Schema(description = "One entry per import source, plus a synthetic 'manual' bucket for hand-created events")
    val perSource: List<SourceQualityMetrics>
)

/**
 * One source's numbers.
 *
 * `suspectNonArtistTitles` is the odd one out and is documented as such: it counts *distinct names*,
 * not events, because a placeholder that appears on forty events is one bad name to fix and not
 * forty. Its percentage is therefore deliberately absent — a percentage of `totalEvents` would be
 * comparing names to events.
 */
@Schema(description = "Data-quality metrics for one event source")
data class SourceQualityMetrics(
    @Schema(description = "Source slug, or 'manual' for events created through the admin API", example = "badehaus")
    val source: String,
    @Schema(description = "Events attributed to this source — the denominator for every percentage below", example = "92")
    val totalEvents: Long,
    @Schema(description = "CONCERT events with no artist linked. The headline gap", example = "72")
    val concertsWithoutArtist: Long,
    @Schema(description = "concertsWithoutArtist as a percentage of totalEvents", example = "78.3")
    val concertsWithoutArtistPct: Double,
    @Schema(description = "Events the classifier could not type", example = "4")
    val eventsTypedOther: Long,
    @Schema(description = "eventsTypedOther as a percentage of totalEvents", example = "4.3")
    val eventsTypedOtherPct: Double,
    @Schema(description = "Events with no genre", example = "31")
    val missingGenre: Long,
    @Schema(description = "missingGenre as a percentage of totalEvents", example = "33.7")
    val missingGenrePct: Double,
    @Schema(description = "Events with no promoter linked", example = "88")
    val missingPromoter: Long,
    @Schema(description = "missingPromoter as a percentage of totalEvents", example = "95.7")
    val missingPromoterPct: Double,
    @Schema(
        description =
            "Events with no price at all — neither presale nor box office, not marked free, and " +
                "carrying no free-form price note. A free event is not a missing price"
    )
    val missingPrice: Long,
    @Schema(description = "missingPrice as a percentage of totalEvents", example = "21.7")
    val missingPricePct: Double,
    @Schema(description = "Events with no start time", example = "12")
    val missingStartTime: Long,
    @Schema(description = "missingStartTime as a percentage of totalEvents", example = "13.0")
    val missingStartTimePct: Double,
    @Schema(
        description =
            "DISTINCT artist names on this source's events that the curated vocabulary says are " +
                "not artists — placeholders, role labels, support-act boilerplate. Counts names, " +
                "not events, which is why it has no percentage: one bad name on forty events is " +
                "one thing to fix"
    )
    val suspectNonArtistTitles: Long
)

/** One event failing one metric, in the shape a steward needs to decide whether to open it. */
@Schema(description = "An event failing one data-quality metric")
data class WorklistEntryResponse(
    @Schema(description = "Event id — the one to PUT /api/admin/events/{id}", example = "418")
    val id: Long,
    @Schema(description = "Event slug", example = "badehaus-die-nerven-2026-09-12")
    val slug: String,
    @Schema(description = "Event title", example = "Die Nerven")
    val title: String,
    @Schema(description = "Event date", example = "2026-09-12")
    val eventDate: LocalDate,
    @Schema(description = "Start time, if known — one of the things that may be missing", example = "20:00:00")
    val startTime: LocalTime?,
    @Schema(description = "Venue id", example = "7")
    val venueId: Long,
    @Schema(description = "Source slug, or 'manual'", example = "badehaus")
    val sourceSlug: String
) {
    companion object {
        fun fromRow(row: WorklistRow): WorklistEntryResponse =
            WorklistEntryResponse(
                id = row.id,
                slug = row.slug,
                title = row.title,
                eventDate = row.eventDate,
                startTime = row.startTime,
                venueId = row.venueId,
                sourceSlug = row.sourceSlug
            )
    }
}

/**
 * A page of the worklist.
 *
 * It carries the issue and the source it was built for. That looks redundant next to the request
 * that produced it and is not: this payload gets pasted into an issue or a message, and a list of
 * event ids with no statement of what is wrong with them is a list nobody can act on a week later.
 */
@Schema(description = "A page of events failing one data-quality metric")
data class WorklistResponse(
    @Schema(description = "The metric these events fail", example = "concertsWithoutArtist")
    val issue: String,
    @Schema(description = "The source filter that was applied, or null for all sources", example = "badehaus")
    val source: String?,
    @Schema(description = "Number of entries in this page", example = "20")
    val count: Int,
    @Schema(description = "The offending events")
    val entries: List<WorklistEntryResponse>
)

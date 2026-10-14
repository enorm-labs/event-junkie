package de.norm.events.dataquality

import de.norm.events.common.PageResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The review surface over `event_suggestion` (#474 part A): list the proposals, accept or dismiss one.
 *
 * **Nothing here writes to `event`.** A check proposes and a person decides (ADR-041 rule 3). The
 * person applies an accepted fix through `PUT /api/admin/events/{id}`, which pins the field so the
 * next import does not undo it (ADR-042), and then marks the suggestion accepted here. The admin
 * view (#346) does both.
 *
 * Under `/api/admin`, so cluster-internal and behind basic auth like [DataQualityController].
 */
@RestController
@RequestMapping("/api/admin/data-quality/suggestions")
@Tag(name = "Admin: Data Quality", description = "Per-source data-quality measurement (Pillar 1 — Measure)")
class EventSuggestionController(
    private val repository: EventSuggestionRepository
) {
    private val logger = KotlinLogging.logger {}

    @Operation(
        summary = "Proposed fixes, each with its evidence",
        description =
            "Newest first, with id as a tie-break so a page boundary is stable. The order is fixed: " +
                "a `sort` parameter is ignored. A suggestion is a proposal and never a write: apply " +
                "one with PUT /api/admin/events/{id}."
    )
    @GetMapping
    suspend fun list(
        @Parameter(description = "Only the suggestions for this event", example = "4711")
        @RequestParam(required = false) eventId: Long?,
        @Parameter(description = "Only suggestions in this state: OPEN, DISMISSED or ACCEPTED", example = "OPEN")
        @RequestParam(required = false) status: String?,
        @Parameter(description = "Only the suggestions of this check", example = "event-classification")
        @RequestParam(required = false) checkName: String?,
        @ParameterObject
        @PageableDefault(size = 20) pageable: Pageable
    ): PageResponse<EventSuggestionResponse> {
        // An unknown status is a 400 naming the valid values, not an empty page. An empty page is
        // the answer to "nothing is open", and a typo must not look like that answer.
        val resolved =
            status?.let {
                EventSuggestionStatus.byName(it)
                    ?: throw ServerWebInputException(
                        "Unknown status '$it'. Valid values: ${EventSuggestionStatus.entries.joinToString(", ")}"
                    )
            }
        val filter = EventSuggestionFilter(eventId, resolved, checkName?.takeIf { it.isNotBlank() })
        val rows = repository.findPage(filter, pageable.pageSize, pageable.offset)
        return PageResponse.of(rows.map(EventSuggestionResponse::from), pageable, repository.count(filter))
    }

    @Operation(
        summary = "Mark one suggestion accepted",
        description =
            "Sets the status to ACCEPTED and returns the suggestion. It writes no event field: apply the " +
                "value with PUT /api/admin/events/{id} first, which pins the field. Accepting an accepted " +
                "one again is a no-op. An unknown id is a 404 and a dismissed suggestion is a 409."
    )
    @PostMapping("/{id}/accept")
    suspend fun accept(
        @Parameter(description = "Database ID of the suggestion", example = "1")
        @PathVariable id: Long
    ): EventSuggestionResponse {
        val changed =
            repository.updateStatus(
                id,
                EventSuggestionStatus.ACCEPTED,
                onlyFrom = setOf(EventSuggestionStatus.OPEN)
            )
        val row = repository.findById(id) ?: throw EventSuggestionNotFoundException(id)
        if (row.status == EventSuggestionStatus.DISMISSED) throw EventSuggestionAlreadyDismissedException(id)
        if (changed > 0) logger.info { "Suggestion $id accepted: ${row.field} of event ${row.eventId}, from check ${row.checkName}" }
        return EventSuggestionResponse.from(row)
    }

    @Operation(
        summary = "Dismiss one suggestion",
        description =
            "Sets the status to DISMISSED and returns the suggestion. Dismissing a dismissed one " +
                "again is a no-op. An unknown id is a 404 and an accepted suggestion is a 409: " +
                "a fix that was applied is not taken back by dismissing its proposal."
    )
    @PostMapping("/{id}/dismiss")
    suspend fun dismiss(
        @Parameter(description = "Database ID of the suggestion", example = "1")
        @PathVariable id: Long
    ): EventSuggestionResponse {
        val changed =
            repository.updateStatus(
                id,
                EventSuggestionStatus.DISMISSED,
                onlyFrom = setOf(EventSuggestionStatus.OPEN)
            )
        val row = repository.findById(id) ?: throw EventSuggestionNotFoundException(id)
        if (row.status == EventSuggestionStatus.ACCEPTED) throw EventSuggestionAlreadyAcceptedException(id)
        if (changed > 0) logger.info { "Suggestion $id dismissed: ${row.field} of event ${row.eventId}, from check ${row.checkName}" }
        return EventSuggestionResponse.from(row)
    }
}

/** Thrown when no suggestion has the given id. Rendered as a 404 Problem Detail. */
class EventSuggestionNotFoundException(
    id: Long
) : RuntimeException("Suggestion with id $id not found")

/** Thrown when a dismissal targets a suggestion somebody already accepted. Rendered as a 409. */
class EventSuggestionAlreadyAcceptedException(
    id: Long
) : RuntimeException("Suggestion with id $id is already accepted and cannot be dismissed")

/** Thrown when an acceptance targets a suggestion somebody already dismissed. Rendered as a 409. */
class EventSuggestionAlreadyDismissedException(
    id: Long
) : RuntimeException("Suggestion with id $id is dismissed and cannot be accepted")

@Schema(description = "One proposed fix for one field of one event")
data class EventSuggestionResponse(
    @Schema(example = "1")
    val id: Long,
    @Schema(example = "4711")
    val eventId: Long,
    @Schema(description = "The event's title", example = "The Adicts")
    val eventTitle: String,
    @Schema(description = "The event's slug, the last part of its public URL", example = "2026-10-24-the-adicts")
    val eventSlug: String,
    @Schema(description = "The day the event takes place", example = "2026-10-24")
    val eventDate: LocalDate,
    @Schema(description = "The name of the event's venue", example = "Astra Kulturhaus")
    val venueName: String,
    @Schema(description = "The event field, as PUT /api/admin/events/{id} names it", example = "startTime")
    val field: String,
    @Schema(description = "What the event holds now. Null when the field is empty", example = "null", nullable = true)
    val storedValue: String?,
    @Schema(description = "What the check proposes", example = "20:00")
    val proposedValue: String,
    @Schema(description = "What the check saw: a sentence of the description or a passage of the source page", example = "Einlass 19 Uhr, Beginn 20 Uhr")
    val evidence: String,
    @Schema(description = "The check's confidence, from 0 to 1", example = "0.92")
    val confidence: Double,
    @Schema(description = "Which check made the proposal", example = "description-start-time")
    val checkName: String,
    @Schema(description = "The model that made it. Null for a deterministic check", example = "claude-haiku-4-5", nullable = true)
    val model: String?,
    val status: EventSuggestionStatus,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(row: EventSuggestionRow): EventSuggestionResponse =
            EventSuggestionResponse(
                id = row.id,
                eventId = row.eventId,
                eventTitle = row.event.title,
                eventSlug = row.event.slug,
                eventDate = row.event.eventDate,
                venueName = row.event.venueName,
                field = row.field,
                storedValue = row.storedValue,
                proposedValue = row.proposedValue,
                evidence = row.evidence,
                confidence = row.confidence,
                checkName = row.checkName,
                model = row.model,
                status = row.status,
                createdAt = row.createdAt
            )
    }
}

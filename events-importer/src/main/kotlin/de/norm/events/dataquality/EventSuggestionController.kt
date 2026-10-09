package de.norm.events.dataquality

import de.norm.events.common.PageResponse
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
import java.time.OffsetDateTime

/**
 * The review surface over `event_suggestion` (#474 part A): list the proposals, dismiss one.
 *
 * **Nothing here writes to `event`.** A check proposes and a person decides (ADR-041 rule 3). The
 * person applies an accepted fix through `PUT /api/admin/events/{id}`, which pins the field so the
 * next import does not undo it (ADR-042). The admin view (#346) is the client.
 *
 * Under `/api/admin`, so cluster-internal and behind basic auth like [DataQualityController].
 */
@RestController
@RequestMapping("/api/admin/data-quality/suggestions")
@Tag(name = "Admin: Data Quality", description = "Per-source data-quality measurement (Pillar 1 — Measure)")
class EventSuggestionController(
    private val repository: EventSuggestionRepository
) {
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
        val rows = repository.findPage(eventId, resolved, pageable.pageSize, pageable.offset)
        return PageResponse.of(rows.map(EventSuggestionResponse::from), pageable, repository.count(eventId, resolved))
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
        repository.updateStatus(
            id,
            EventSuggestionStatus.DISMISSED,
            onlyFrom = setOf(EventSuggestionStatus.OPEN, EventSuggestionStatus.DISMISSED)
        )
        val row = repository.findById(id) ?: throw EventSuggestionNotFoundException(id)
        if (row.status == EventSuggestionStatus.ACCEPTED) throw EventSuggestionAlreadyAcceptedException(id)
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

@Schema(description = "One proposed fix for one field of one event")
data class EventSuggestionResponse(
    @Schema(example = "1")
    val id: Long,
    @Schema(example = "4711")
    val eventId: Long,
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

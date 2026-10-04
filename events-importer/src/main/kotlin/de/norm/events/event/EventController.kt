package de.norm.events.event

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Admin REST controller for managing events.
 *
 * Events are the core entity linking venues, artists, and promoters.
 * Create and update operations accept artist/promoter IDs and manage
 * the join-table associations transactionally.
 */
@RestController
@RequestMapping("/api/admin/events")
@Tag(name = "Admin: Events", description = "Admin CRUD endpoints for managing events")
class EventController(
    private val eventService: EventService,
    private val eventPinService: EventPinService
) {
    /**
     * Lists events with pagination. Returns a [List] instead of a `Flow` because
     * batch loading artist/promoter/genre tag associations requires all events in memory
     * to avoid N+1 queries (4 queries per page regardless of event count).
     */
    @GetMapping
    @Operation(summary = "List all events with pagination")
    suspend fun findAll(
        @ParameterObject
        @PageableDefault(size = 20, sort = ["eventDate"]) pageable: Pageable
    ): PageResponse<EventResponse> = eventService.findAll(pageable)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single event by ID")
    suspend fun findById(
        @Parameter(description = "Database ID of the event.", example = "1")
        @PathVariable id: Long
    ): EventResponse = eventService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new event")
    suspend fun create(
        @Valid @RequestBody request: EventRequest
    ): EventResponse = eventService.create(request)

    /** Replaces the event. Each field whose value changes is pinned, and the importer keeps it (ADR-042). */
    @PutMapping("/{id}")
    @Operation(summary = "Update an existing event, pinning each field the edit changes")
    suspend fun update(
        @Parameter(description = "Database ID of the event.", example = "1")
        @PathVariable id: Long,
        @Valid @RequestBody request: EventRequest
    ): EventResponse = eventService.update(id, request)

    /**
     * Replays language detection over stored descriptions that carry no language.
     *
     * A one-off for the rows that predate detection (#470). Every import classifies what it writes,
     * so this endpoint finds nothing on a database that has imported since.
     */
    @PostMapping("/detect-languages")
    @Operation(summary = "Classify the language of stored descriptions that carry none")
    suspend fun detectLanguages(): DescriptionLanguageBackfill = eventService.classifyStoredDescriptions()

    /**
     * Removes the pin a hand edit set on one field (ADR-042). The next import that reads the event's
     * page writes the source's value; a one-page source whose page is unchanged answers 304 until
     * `?force=true`.
     */
    @DeleteMapping("/{id}/pins/{field}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a pin, so the next import writes the source's value of that field")
    suspend fun unpin(
        @Parameter(description = "Database ID of the event.", example = "1")
        @PathVariable id: Long,
        @Parameter(description = "The pinned field, as `pinnedFields` names it.", example = "title")
        @PathVariable field: String
    ) {
        eventPinService.unpin(id, field)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an event by ID")
    suspend fun delete(
        @Parameter(description = "Database ID of the event.", example = "1")
        @PathVariable id: Long
    ) {
        eventService.delete(id)
    }
}

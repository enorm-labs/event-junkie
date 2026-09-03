package de.norm.events.event

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
    private val eventService: EventService
) {
    /**
     * Lists events with pagination. Returns a [List] instead of a `Flow` because
     * batch loading artist/promoter/genre tag associations requires all events in memory
     * to avoid N+1 queries (4 queries per page regardless of event count).
     */
    @GetMapping
    @Operation(summary = "List all events with pagination")
    suspend fun findAll(
        @PageableDefault(size = 20, sort = ["eventDate"]) pageable: Pageable
    ): PageResponse<EventResponse> = eventService.findAll(pageable)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single event by ID")
    suspend fun findById(
        @PathVariable id: Long
    ): EventResponse = eventService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new event")
    suspend fun create(
        @Valid @RequestBody request: EventRequest
    ): EventResponse = eventService.create(request)

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing event")
    suspend fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: EventRequest
    ): EventResponse = eventService.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an event by ID")
    suspend fun delete(
        @PathVariable id: Long
    ) {
        eventService.delete(id)
    }
}

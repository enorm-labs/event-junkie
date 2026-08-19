package de.norm.events.venue

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
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
 * Admin REST controller for managing venues.
 *
 * All endpoints live under `/api/admin/venues` and use coroutine suspend
 * functions for non-blocking request handling.
 */
@RestController
@RequestMapping("/api/admin/venues")
@Tag(name = "Admin: Venues", description = "Admin CRUD endpoints for managing venues")
class VenueController(
    private val venueService: VenueService
) {
    /** Lists venues with pagination and sorting. */
    @GetMapping
    @Operation(summary = "List all venues with pagination")
    fun findAll(
        @PageableDefault(size = 20, sort = ["name"]) pageable: Pageable
    ): Flow<VenueResponse> = venueService.findAll(pageable)

    /** Retrieves a single venue by its database ID. */
    @GetMapping("/{id}")
    @Operation(summary = "Get a single venue by ID")
    suspend fun findById(
        @PathVariable id: Long
    ): VenueResponse = venueService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new venue")
    suspend fun create(
        @Valid @RequestBody request: VenueRequest
    ): VenueResponse = venueService.create(request)

    /** Replaces an existing venue identified by [id]. */
    @PutMapping("/{id}")
    @Operation(summary = "Update an existing venue")
    suspend fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: VenueRequest
    ): VenueResponse = venueService.update(id, request)

    /** Deletes a venue by its database ID. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a venue by ID")
    suspend fun delete(
        @PathVariable id: Long
    ) {
        venueService.delete(id)
    }
}

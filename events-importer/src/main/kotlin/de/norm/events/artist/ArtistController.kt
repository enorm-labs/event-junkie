package de.norm.events.artist

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
 * Admin REST controller for managing artists.
 */
@RestController
@RequestMapping("/api/admin/artists")
@Tag(name = "Admin: Artists", description = "Admin CRUD endpoints for managing artists")
class ArtistController(
    private val artistService: ArtistService
) {
    @GetMapping
    @Operation(summary = "List all artists with pagination")
    suspend fun findAll(
        @PageableDefault(size = 20, sort = ["name"]) pageable: Pageable
    ): PageResponse<ArtistResponse> = artistService.findAll(pageable)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single artist by ID")
    suspend fun findById(
        @PathVariable id: Long
    ): ArtistResponse = artistService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new artist")
    suspend fun create(
        @Valid @RequestBody request: ArtistRequest
    ): ArtistResponse = artistService.create(request)

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing artist")
    suspend fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: ArtistRequest
    ): ArtistResponse = artistService.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an artist by ID")
    suspend fun delete(
        @PathVariable id: Long
    ) {
        artistService.delete(id)
    }
}

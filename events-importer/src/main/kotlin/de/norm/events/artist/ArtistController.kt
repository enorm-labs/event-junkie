package de.norm.events.artist

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
import org.springframework.web.bind.annotation.RequestParam
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
    @Operation(summary = "List all artists with pagination; `name` narrows to the names that contain it, ignoring case")
    suspend fun findAll(
        @ParameterObject
        @PageableDefault(size = 20, sort = ["name"]) pageable: Pageable,
        @Parameter(description = "Part of the name, any letter case. Omit it to list all.", example = "adicts")
        @RequestParam(required = false) name: String?
    ): PageResponse<ArtistResponse> = artistService.findAll(pageable, name)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single artist by ID")
    suspend fun findById(
        @Parameter(description = "Database ID of the artist.", example = "1")
        @PathVariable id: Long
    ): ArtistResponse = artistService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new artist")
    suspend fun create(
        @Valid @RequestBody request: ArtistRequest
    ): ArtistResponse = artistService.create(request)

    /** Replaces the artist. A changed name is pinned, and the MusicBrainz enrichment keeps it (ADR-042). */
    @PutMapping("/{id}")
    @Operation(summary = "Update an existing artist, pinning the name when the edit changes it")
    suspend fun update(
        @Parameter(description = "Database ID of the artist.", example = "1")
        @PathVariable id: Long,
        @Valid @RequestBody request: ArtistRequest
    ): ArtistResponse = artistService.update(id, request)

    /**
     * Removes the pin a hand edit set on the name (ADR-042). The next enrichment reads the row again
     * and, on an EXACT match, writes MusicBrainz's letter case.
     */
    @DeleteMapping("/{id}/pins/name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the name's pin, so the next MusicBrainz enrichment may change its letter case")
    suspend fun unpinName(
        @Parameter(description = "Database ID of the artist.", example = "1")
        @PathVariable id: Long
    ) {
        artistService.unpinName(id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an artist by ID")
    suspend fun delete(
        @Parameter(description = "Database ID of the artist.", example = "1")
        @PathVariable id: Long
    ) {
        artistService.delete(id)
    }
}

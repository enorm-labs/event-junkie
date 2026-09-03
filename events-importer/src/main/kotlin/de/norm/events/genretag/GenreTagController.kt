package de.norm.events.genretag

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only REST controller for genre tags.
 *
 * Genre tags are auto-created during event imports — there is no manual
 * create/update/delete API. This controller exposes the available tags
 * so the frontend can populate filter dropdowns and link to filtered views.
 */
@RestController
@RequestMapping("/api/admin/genre-tags")
@Tag(name = "Admin: Genre Tags", description = "Read-only endpoints for normalized genre tags")
class GenreTagController(
    private val genreTagService: GenreTagService
) {
    @GetMapping
    @Operation(summary = "List all genre tags with pagination")
    suspend fun findAll(
        // 100 rather than the 20 the other admin lists default to, and deliberately so: genre tags
        // are a small bounded table read to fill a dropdown, so one request usually returns all of
        // them. The envelope reports the total either way, which is what makes the difference a
        // convenience rather than a trap (#810).
        @PageableDefault(size = 100, sort = ["name"]) pageable: Pageable
    ): PageResponse<GenreTagResponse> = genreTagService.findAll(pageable)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single genre tag by ID")
    suspend fun findById(
        @PathVariable id: Long
    ): GenreTagResponse = genreTagService.findById(id)
}

package de.norm.events.genretag

import de.norm.events.common.QueryParameters
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Public read API exposing the genre tag list for the frontend event filter.
 */
@RestController
@RequestMapping("/genres")
@Tag(name = "Genres", description = "Public endpoint listing genre tags for filtering")
class GenreTagController(
    private val genreTagService: GenreTagService
) {
    @GetMapping
    @Operation(summary = "List all genre tags alphabetically")
    suspend fun list(exchange: ServerWebExchange): List<GenreTagResponse> {
        NO_PARAMS.rejectUnknownIn(exchange)
        return genreTagService.list()
    }

    private companion object {
        /** The whole list is always returned, so this endpoint takes no parameters at all. */
        val NO_PARAMS = QueryParameters.accepting()
    }
}

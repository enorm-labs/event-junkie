package de.norm.events.artist

import de.norm.events.common.PageResponse
import de.norm.events.common.QueryParameters
import de.norm.events.common.ResponseCache
import de.norm.events.common.TextSearch
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Public read API for artists.
 */
@RestController
@RequestMapping("/api/artists")
@Tag(name = "Artists", description = "Public endpoints for browsing and searching artists")
class ArtistController(
    private val artistService: ArtistService,
    private val cache: ResponseCache
) {
    @GetMapping
    @Operation(
        summary = "List artists with pagination and optional name search, sorted by name",
        description =
            "Sort with `sort=name` (A–Z, case-folded). A chosen sort applies with a search too. " +
                "Without a sort, a search lists the closest names first, else A–Z."
    )
    suspend fun list(
        @Parameter(description = "Search on the artist name: ignores case, accents and spaces, and forgives small typos. Omitted/blank returns all artists.")
        @RequestParam(required = false)
        q: String?,
        @ParameterObject
        @PageableDefault(size = 20)
        pageable: Pageable,
        exchange: ServerWebExchange
    ): PageResponse<ArtistSummaryResponse> {
        LIST_PARAMS.rejectUnknownIn(exchange)
        val term = TextSearch.term(q)
        return cache.get(ArtistListKey(term, pageable)) { artistService.list(term, pageable) }
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a single artist by slug")
    suspend fun findBySlug(
        @Parameter(description = "Unique artist slug.", example = "actors", required = true)
        @PathVariable slug: String
    ): ArtistDetailResponse = cache.get(ArtistDetailKey(slug)) { artistService.findBySlug(slug) }

    private companion object {
        /** Declared rather than derived: these parameters are on the method, not on a filter object. */
        val LIST_PARAMS = QueryParameters.accepting(QueryParameters.PAGEABLE, QueryParameters.named("q"))
    }
}

/** The cache keys this controller owns. Separate types, so no endpoint can collide with another. */
private data class ArtistListKey(
    val query: String?,
    val pageable: Pageable
)

private data class ArtistDetailKey(
    val slug: String
)

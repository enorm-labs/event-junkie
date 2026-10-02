package de.norm.events.venue

import de.norm.events.common.PageResponse
import de.norm.events.common.QueryParameters
import de.norm.events.common.ResponseCache
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Public read API for venues.
 */
@RestController
@RequestMapping("/api/venues")
@Tag(name = "Venues", description = "Public endpoints for browsing venues")
class VenueController(
    private val venueService: VenueService,
    private val cache: ResponseCache
) {
    @GetMapping
    @Operation(summary = "List venues with pagination, name search and filters, sorted by name or by upcoming events")
    suspend fun list(
        @ParameterObject
        filters: VenueFilterParams,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["name"])
        pageable: Pageable,
        exchange: ServerWebExchange
    ): PageResponse<VenueListItemResponse> {
        LIST_PARAMS.rejectUnknownIn(exchange)
        val filter = filters.toFilter()
        return cache.get(VenueListKey(filter, pageable)) { venueService.list(filter, pageable) }
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a single venue by slug")
    suspend fun findBySlug(
        @Parameter(description = "Unique venue slug.", example = "lido", required = true)
        @PathVariable slug: String
    ): VenueDetailResponse = cache.get(VenueDetailKey(slug)) { venueService.findBySlug(slug) }

    private companion object {
        /** The filter fields come from [VenueFilterParams]; paging is declared here. */
        val LIST_PARAMS = QueryParameters.accepting(VenueFilterParams::class.java, QueryParameters.PAGEABLE)
    }
}

/** The cache keys this controller owns. Separate types, so no endpoint can collide with another. */
private data class VenueListKey(
    val filter: VenueFilter,
    val pageable: Pageable
)

private data class VenueDetailKey(
    val slug: String
)

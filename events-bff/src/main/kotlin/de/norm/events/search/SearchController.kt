package de.norm.events.search

import de.norm.events.common.QueryParameters
import de.norm.events.common.TextSearch
import de.norm.events.event.BffMetrics
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

/**
 * The header search (#2514): events, venues, artists and promoters for one term, in one request.
 *
 * Not in the [de.norm.events.common.ResponseCache]: type-ahead asks for every prefix of a word, so
 * each entry would be read about once and would push out tonight's feed and the detail pages.
 */
@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "One search across events, venues, artists and promoters")
class SearchController(
    private val searchService: SearchService,
    private val metrics: BffMetrics
) {
    @GetMapping
    @Operation(summary = "The first matches of each kind for a search term")
    suspend fun search(
        @Parameter(
            description = "Search term, at least $MIN_TERM_LENGTH characters: ignores case, accents and spaces, and forgives small typos.",
            required = true
        )
        @RequestParam(required = false)
        q: String?,
        @Parameter(description = "Most items per kind, 1 to $MAX_LIMIT.", example = "5")
        @RequestParam(defaultValue = "$DEFAULT_LIMIT")
        limit: Int,
        @Parameter(description = "Also return the events that are over, in `past`. Leave it out for type-ahead: it is one more query.")
        @RequestParam(defaultValue = "false")
        past: Boolean,
        exchange: ServerWebExchange
    ): SearchResponse {
        PARAMS.rejectUnknownIn(exchange)
        val term = TextSearch.term(q)
        if (term == null || term.length < MIN_TERM_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'q' must have at least $MIN_TERM_LENGTH characters")
        }
        if (limit !in 1..MAX_LIMIT) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'limit' must be between 1 and $MAX_LIMIT")
        return searchService.search(term, limit, past).also {
            metrics.recordServed(BffMetrics.ENDPOINT_GLOBAL_SEARCH, it.events.items.size)
        }
    }

    private companion object {
        /** One letter matches most of the catalogue, and a typo pass needs four (`TextSearch.allowsSimilar`). */
        const val MIN_TERM_LENGTH = 2
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 20

        val PARAMS = QueryParameters.accepting(QueryParameters.named("q", "limit", "past"))
    }
}

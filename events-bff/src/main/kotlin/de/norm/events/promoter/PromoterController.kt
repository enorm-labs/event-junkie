package de.norm.events.promoter

import de.norm.events.common.PageResponse
import de.norm.events.common.QueryParameters
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
 * Public read API for promoters.
 */
@RestController
@RequestMapping("/api/promoters")
@Tag(name = "Promoters", description = "Public endpoints for browsing promoters")
class PromoterController(
    private val promoterService: PromoterService
) {
    @GetMapping
    @Operation(summary = "List promoters with pagination and optional name search")
    suspend fun list(
        @Parameter(description = "Case-insensitive substring filter on the promoter name. Omitted/blank returns all promoters.")
        @RequestParam(required = false)
        q: String?,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["name"])
        pageable: Pageable,
        exchange: ServerWebExchange
    ): PageResponse<PromoterSummaryResponse> {
        LIST_PARAMS.rejectUnknownIn(exchange)
        return promoterService.list(q, pageable)
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a single promoter by slug")
    suspend fun findBySlug(
        @Parameter(description = "Unique promoter slug.", example = "36-concerts", required = true)
        @PathVariable slug: String
    ): PromoterDetailResponse = promoterService.findBySlug(slug)

    private companion object {
        /** Declared rather than derived: these parameters are on the method, not on a filter object. */
        val LIST_PARAMS = QueryParameters.accepting(QueryParameters.PAGEABLE, QueryParameters.named("q"))
    }
}

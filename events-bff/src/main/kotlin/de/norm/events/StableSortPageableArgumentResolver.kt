package de.norm.events

import org.springframework.core.MethodParameter
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver
import org.springframework.web.reactive.BindingContext
import org.springframework.web.server.ServerWebExchange

/**
 * Resolves [Pageable] like Spring Data's [ReactivePageableHandlerMethodArgumentResolver], then
 * appends `id` as the final sort key so paging is deterministic: every list endpoint sorts by a
 * non-unique column, and with 23 events sharing one date a client walking `?page=0,1,2…` could
 * see a row twice and never see another. In the resolver rather than each `@PageableDefault`,
 * because that only applies when the request carries no `sort`, and the SPA sends one. Always
 * ascending, skipped when the caller already sorts by `id` or for an unpaged request. The
 * filtered event search builds its own `ORDER BY` ending in `e.id ASC` and allowlists sort
 * properties, so this covers the derived `findAllBy(pageable)` endpoints.
 *
 * [maxPageSize] is applied here because nothing else can: `DataWebAutoConfiguration` is
 * `@ConditionalOnWebApplication(type = SERVLET)`, so `spring.data.web.pageable.max-page-size`
 * reaches nothing on WebFlux, and the default is 2000 (#268).
 */
class StableSortPageableArgumentResolver(
    maxPageSize: Int
) : ReactivePageableHandlerMethodArgumentResolver() {
    init {
        setMaxPageSize(maxPageSize)
    }

    override fun resolveArgumentValue(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange
    ): Pageable {
        val pageable = super.resolveArgumentValue(parameter, bindingContext, exchange)
        if (pageable.isUnpaged || pageable.sort.getOrderFor(TIEBREAKER_PROPERTY) != null) return pageable
        return PageRequest.of(
            pageable.pageNumber,
            pageable.pageSize,
            pageable.sort.and(Sort.by(TIEBREAKER_PROPERTY))
        )
    }

    private companion object {
        /** The primary key every paged entity carries — unique, so it fully determines the order. */
        private const val TIEBREAKER_PROPERTY = "id"
    }
}

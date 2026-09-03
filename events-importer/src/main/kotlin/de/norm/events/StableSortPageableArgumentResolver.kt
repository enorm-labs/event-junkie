package de.norm.events

import org.springframework.core.MethodParameter
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver
import org.springframework.web.reactive.BindingContext
import org.springframework.web.server.ServerWebExchange

/**
 * Resolves [Pageable] request parameters exactly like Spring Data's
 * [ReactivePageableHandlerMethodArgumentResolver], then appends `id` as the final sort
 * key so paging is deterministic.
 *
 * Every list endpoint sorts by a **non-unique** column — `name` for venues/artists/
 * promoters/sources, `eventDate` for events — and `LIMIT`/`OFFSET` paging gives PostgreSQL
 * no obligation to order tied rows the same way across two queries. With 23 events sharing
 * one date, a client walking `?page=0,1,2…` could see a row twice and, by pigeonhole, never
 * see another one at all. Appending a unique tiebreaker makes the total order well-defined,
 * so each row appears on exactly one page.
 *
 * Fixing this in the resolver rather than in each `@PageableDefault` declaration is
 * deliberate: `@PageableDefault` only applies when the request carries **no** `sort`
 * parameter, and the SPA sends one, so a default-only tiebreaker would leave the real
 * paging path unstable. Here it covers both.
 *
 * The tiebreaker is always ascending — within a group of tied rows any fixed order is
 * correct, only its *stability* matters — and is skipped when the caller already sorts by
 * `id`, or for an unpaged request (no paging, nothing to keep stable).
 *
 * The BFF's filtered event search builds its `ORDER BY` by hand and already ends it with
 * `e.id ASC`; it allowlists sort properties, so the key appended here is simply ignored
 * there. This resolver is what covers the derived `findAllBy(pageable)` endpoints.
 *
 * [maxPageSize] has to be applied here because nothing else can. `DataWebAutoConfiguration` is
 * `@ConditionalOnWebApplication(type = SERVLET)`, so `spring.data.web.pageable.max-page-size`
 * reaches nothing on WebFlux, and unset the cap is Spring Data's own default of 2000.
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

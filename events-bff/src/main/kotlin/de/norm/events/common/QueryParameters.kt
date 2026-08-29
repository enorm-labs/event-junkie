package de.norm.events.common

import org.springframework.beans.BeanUtils
import org.springframework.web.server.ServerWebExchange

/**
 * The query parameters one endpoint accepts, and the check that rejects anything else.
 *
 * WebFlux binds the parameters it recognises and drops the rest without a word, so a caller who
 * misspells a filter name gets the **unfiltered** collection and a `200`. The asymmetry is what
 * makes it dangerous: a wrong *value* (`venue=NONEXISTENT`) correctly returns nothing, while a
 * wrong *name* (`venueSlug=…`) silently widens the result set. Measured on staging, one misspelt
 * parameter returned 3,283 events where 11 were asked for — reported as success (#815).
 *
 * A narrowing that is discarded cannot be noticed by the caller, which is why this fails closed.
 * The cost is real and accepted: a client that appends a tracking parameter now gets a `400`
 * instead of being quietly ignored. For a *filter* API that is the better of the two failures.
 *
 * Names come from [BeanUtils.getPropertyDescriptors], the same source the data binder uses to
 * decide what it can bind, so a filter field added to [de.norm.events.event.EventFilterParams] is
 * accepted here without a second edit. What each endpoint declares is only the surrounding
 * parameters — `page`/`size`/`sort`, and any it takes directly.
 */
class QueryParameters private constructor(
    private val accepted: Set<String>
) {
    /**
     * Fails with [UnknownQueryParameterException] when [exchange] carries a parameter this
     * endpoint does not accept.
     */
    fun rejectUnknownIn(exchange: ServerWebExchange) {
        val unknown =
            exchange.request.queryParams.keys
                .filterNot { it in accepted }
        if (unknown.isNotEmpty()) throw UnknownQueryParameterException(unknown.sorted(), accepted.sorted())
    }

    companion object {
        /**
         * `page`, `size` and `sort` — contributed by [org.springframework.data.domain.Pageable] on
         * every paginated endpoint, and never declared on a filter object. Forgetting these would
         * break every paging client, which is why they are a named constant rather than three
         * string literals per call site.
         */
        val PAGEABLE = setOf("page", "size", "sort")

        /** Accepts [extra] and nothing else. Use for an endpoint with no filter object. */
        fun accepting(vararg extra: Set<String>): QueryParameters = QueryParameters(extra.flatMap { it }.toSet())

        /** Accepts every bindable property of [filters], plus [extra]. */
        fun <T : Any> accepting(
            filters: Class<T>,
            vararg extra: Set<String>
        ): QueryParameters =
            QueryParameters(
                BeanUtils
                    .getPropertyDescriptors(filters)
                    .map { it.name }
                    .filterNot { it == "class" }
                    .toSet() + extra.flatMap { it }
            )

        /** Sugar for a set of individually named parameters. */
        fun named(vararg names: String): Set<String> = names.toSet()
    }
}

/**
 * Raised when a request carries a query parameter its endpoint does not accept. Translated to a
 * `400` naming the offenders and listing what is accepted, so the caller can see the typo.
 */
class UnknownQueryParameterException(
    val unknown: List<String>,
    val accepted: List<String>
) : RuntimeException(
        "Unknown query parameter${if (unknown.size == 1) "" else "s"}: ${unknown.joinToString(", ")}. " +
            "Accepted: ${accepted.joinToString(", ")}."
    )

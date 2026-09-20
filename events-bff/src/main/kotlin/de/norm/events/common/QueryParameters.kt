package de.norm.events.common

import org.springframework.beans.BeanUtils
import org.springframework.web.server.ServerWebExchange

/**
 * The query parameters one endpoint accepts, and the check that rejects anything else. WebFlux
 * drops unrecognised parameters without a word, so a misspelt filter name returns the
 * unfiltered collection with a `200`: one misspelt parameter returned 3,283 events where 11
 * were asked for (#815). So this fails closed; a client appending a tracking parameter gets a
 * `400`, the better failure for a filter API.
 *
 * Names come from [BeanUtils.getPropertyDescriptors], the data binder's own source, so a field
 * added to [de.norm.events.event.EventFilterParams] is accepted without a second edit. Each
 * endpoint declares only the surrounding parameters.
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
         * `page`, `size` and `sort`, contributed by [org.springframework.data.domain.Pageable] on every
         * paginated endpoint and never declared on a filter object; a named constant so none is forgotten.
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
 * `400` naming the offenders and listing what is accepted.
 */
class UnknownQueryParameterException(
    val unknown: List<String>,
    val accepted: List<String>
) : RuntimeException(
        "Unknown query parameter${if (unknown.size == 1) "" else "s"}: ${unknown.joinToString(", ")}. " +
            "Accepted: ${accepted.joinToString(", ")}."
    )

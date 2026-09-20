package de.norm.events

import de.norm.events.LogContextConfiguration.Companion.REQUEST_ID
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

/**
 * Emits one INFO access-log line per request once the exchange completes:
 * `GET /venues?q=astra -> 200 (12ms)`; WebFlux logs nothing at INFO by default. Registered with
 * [Ordered.HIGHEST_PRECEDENCE] so the duration is total in-server time.
 *
 * Also establishes the request's log context (#380): `contextWrite` sits at the bottom of the
 * chain because the Reactor context propagates upwards, and [LogContextConfiguration] turns the
 * entry back into an MDC field on whichever thread runs each operator.
 *
 * The id is the exchange's own, not one minted here (#1527): Boot's `DefaultErrorAttributes`
 * puts `request.id` into every error body as `requestId`, so the id a reporter quotes has to be
 * the one the column carries.
 *
 * Actuator requests are handled and not logged: measured on production over six hours, 1,437
 * lines an hour, every one an actuator request, against one that was not. The base path is read
 * from `management.endpoints.web.base-path`, so the suppression follows the property.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter(
    @Value("\${management.endpoints.web.base-path:/actuator}") private val actuatorBasePath: String
) : WebFilter {
    @Suppress("ForbiddenVoid") // Mono<Void> is WebFilter's own return type.
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        val request = exchange.request
        val startNanos = System.nanoTime()
        return chain
            .filter(exchange)
            .doFinally {
                // The request still gets its id and its trip through the chain; only the line is withheld, so
                // timing and context behaviour stay identical for every request.
                if (!isActuatorRequest(request.path.value())) {
                    val durationMs = (System.nanoTime() - startNanos) / 1_000_000
                    val rawQuery = request.uri.rawQuery
                    val query = if (rawQuery == null) "" else "?$rawQuery"
                    val status = exchange.response.statusCode?.value() ?: 0
                    // Fields, not prose (#945). Two values stay in the text: `durationMs`, because
                    // `http.server.requests` carries latency as a histogram, and the query string, because
                    // `?q=astra` is user-typed input and a column is a different act (LEGAL.md §7.5). Both asserted.
                    logger.at(Level.INFO) {
                        message = "${request.path.value()}$query (${durationMs}ms)"
                        payload =
                            mapOf(
                                LogContextConfiguration.HTTP_METHOD to request.method.name(),
                                LogContextConfiguration.PATH to request.path.value(),
                                LogContextConfiguration.HTTP_STATUS to status
                            )
                    }
                }
            }.contextWrite { it.put(REQUEST_ID, request.id) }
    }

    /**
     * Matches the base path and everything under it, not `/actuatorial`, which a prefix comparison
     * would silently unlog.
     */
    private fun isActuatorRequest(path: String): Boolean {
        val base = actuatorBasePath.removeSuffix("/")
        return path == base || path.startsWith("$base/")
    }
}

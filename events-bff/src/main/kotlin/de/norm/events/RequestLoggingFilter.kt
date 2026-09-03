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
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Emits a single INFO access-log line per request once the exchange completes:
 * `GET /venues?q=astra -> 200 (12ms)`. WebFlux does not log requests at INFO by
 * default, so without this the read API is effectively silent in the logs.
 *
 * Registered with [Ordered.HIGHEST_PRECEDENCE] so it wraps the whole filter chain
 * and the measured duration reflects total in-server time.
 *
 * It also establishes the request's log context (#380). `contextWrite` sits at the bottom of the
 * chain because the Reactor context propagates **upwards**: written here, it is visible to every
 * operator above, which is the whole chain. [LogContextConfiguration] is what turns that context
 * entry back into an MDC field on whichever thread ends up running each one.
 *
 * **Actuator requests are handled and not logged**, which is the one exception and worth the
 * paragraph. Kubernetes probes liveness and readiness every few seconds and the collector scrapes
 * `/actuator/prometheus` beside them, so on an idle deployment this filter's own output is
 * effectively all there is: measured on production over six hours, 1,437 lines an hour, every one
 * of them an actuator request, against **one** line that was not. Logging them buries the requests
 * somebody might actually read, and a log nobody can find anything in has the same value as no log.
 *
 * The base path is read from `management.endpoints.web.base-path` rather than written here, so the
 * suppression follows the property instead of silently ceasing to match if anyone moves it. The
 * default repeats Spring's own.
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
                // The request still gets its id and its trip through the chain; only the line is
                // withheld. Keeping the filter in the path for actuator traffic means the timing
                // and context behaviour stay identical for every request, and one `if` is the whole
                // difference between them.
                if (!isActuatorRequest(request.path.value())) {
                    val durationMs = (System.nanoTime() - startNanos) / 1_000_000
                    val rawQuery = request.uri.rawQuery
                    val query = if (rawQuery == null) "" else "?$rawQuery"
                    val status = exchange.response.statusCode?.value() ?: 0
                    // Fields, not prose (#945) — the only per-request line the read API produces.
                    // Two values deliberately stay in the text: `durationMs`, because
                    // `http.server.requests` already carries latency as a histogram, and the query
                    // string, because `?q=astra` is user-typed input and a column is a different act
                    // from a line (LEGAL.md §7.5). Both are asserted, so a tidy-up has to choose
                    // them rather than drift into them.
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
            }.contextWrite { it.put(REQUEST_ID, UUID.randomUUID().toString()) }
    }

    /**
     * Matches the base path itself and everything under it, and nothing that merely starts with the
     * same letters — `/actuatorial` is a route this application could add tomorrow, and it would be
     * silently unlogged if this compared prefixes alone.
     */
    private fun isActuatorRequest(path: String): Boolean {
        val base = actuatorBasePath.removeSuffix("/")
        return path == base || path.startsWith("$base/")
    }
}

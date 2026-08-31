package de.norm.events

import de.norm.events.LogContextConfiguration.Companion.REQUEST_ID
import io.github.oshai.kotlinlogging.KotlinLogging
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        val request = exchange.request
        val startNanos = System.nanoTime()
        return chain
            .filter(exchange)
            .doFinally {
                val durationMs = (System.nanoTime() - startNanos) / 1_000_000
                val query = request.uri.rawQuery?.let { "?$it" } ?: ""
                val status = exchange.response.statusCode?.value() ?: 0
                logger.info { "${request.method} ${request.path.value()}$query -> $status (${durationMs}ms)" }
            }.contextWrite { it.put(REQUEST_ID, UUID.randomUUID().toString()) }
    }
}

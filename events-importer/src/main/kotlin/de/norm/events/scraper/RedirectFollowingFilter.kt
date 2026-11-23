package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.slf4j.MDCContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import java.net.URI

/** Raised when a scraper request redirects more than [RedirectFollowingFilter.MAX_HOPS] times, or to a `Location` that is not a URL. */
class RedirectFailedException(
    val url: String,
    reason: String
) : RuntimeException("Redirect from $url not followed: $reason")

/**
 * Follows a scraper request's redirects in the filter chain, so each hop is a request of its own (#5417).
 *
 * It is the outermost filter of [SCRAPER_WEB_CLIENT], and the client below it follows no redirect. A
 * hop therefore passes [RobotsTxtFilter] and [PerHostThrottlingFilter] for its own host, and the
 * host's `User-Agent` and response timeout. Reactor Netty followed redirects below the filters, so a
 * redirect to another host skipped that host's `robots.txt` and throttle (ADR-007).
 *
 * 301, 302, 307 and 308 keep the method and the body; 303 becomes a `GET` without a body, as Reactor
 * Netty did. A hop to another host drops the `Cookie` and `Authorization` headers, because they belong
 * to the first host, and logs one `INFO` line with both hosts.
 */
class RedirectFollowingFilter : ExchangeFilterFunction {
    private val logger = KotlinLogging.logger {}

    override fun filter(
        request: ClientRequest,
        next: ExchangeFunction
    ): Mono<ClientResponse> =
        // MDCContext keeps the run's sourceSlug on the hop's line and on the filters the hop passes (#3754).
        mono(MDCContext()) { follow(request, next) }

    private suspend fun follow(
        request: ClientRequest,
        next: ExchangeFunction
    ): ClientResponse {
        var current = request
        var hops = 0
        while (true) {
            val response = next.exchange(current).awaitSingle()
            val status = response.statusCode().value()
            val location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION)
            if (status !in REDIRECT_STATUSES || location == null) return response
            response.releaseBody().awaitSingleOrNull()
            if (hops == MAX_HOPS) throw RedirectFailedException(request.url().toString(), "more than $MAX_HOPS redirects")
            val target =
                runCatching { current.url().resolve(location) }.getOrNull()
                    ?: throw RedirectFailedException(current.url().toString(), "Location '$location' is not a URL")
            current = hop(current, status, target)
            hops++
        }
    }

    private fun hop(
        from: ClientRequest,
        status: Int,
        target: URI
    ): ClientRequest {
        val fromHost = from.url().host
        val crossHost = !target.host.equals(fromHost, ignoreCase = true)
        if (crossHost) {
            logger.at(Level.INFO) {
                message = "Following a $status from $fromHost to ${target.host}"
                payload = mapOf(LogFields.URL to from.url().toString())
            }
        }
        val toGet = status == SEE_OTHER && from.method() != HttpMethod.GET && from.method() != HttpMethod.HEAD
        return ClientRequest
            .from(from)
            .url(target)
            .apply {
                if (toGet) {
                    method(HttpMethod.GET)
                    body(BodyInserters.empty<Any>())
                    headers {
                        it.remove(HttpHeaders.CONTENT_TYPE)
                        it.remove(HttpHeaders.CONTENT_LENGTH)
                    }
                }
                if (crossHost) {
                    headers {
                        it.remove(HttpHeaders.COOKIE)
                        it.remove(HttpHeaders.AUTHORIZATION)
                    }
                    cookies { it.clear() }
                }
            }.build()
    }

    companion object {
        /** The redirects a request follows before it fails; a browser allows about 20, and a venue page needs one or two. */
        const val MAX_HOPS = 5

        private const val SEE_OTHER = 303
        private val REDIRECT_STATUSES = setOf(301, 302, SEE_OTHER, 307, 308)
    }
}

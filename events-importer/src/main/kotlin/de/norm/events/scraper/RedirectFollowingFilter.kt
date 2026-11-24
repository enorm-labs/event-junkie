package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
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
 * Raised when a request or one of its redirects points at a URL the [RedirectTargetGuard] refuses (#5444, #5448).
 * Nothing was sent to it. [fromHost] is the redirecting host, and null for the first request.
 *
 * The message names the hosts and not the URLs, because it reaches the log and `event_source.last_error`, and a
 * query string has no place in either.
 */
class RequestRefusedException(
    val fromHost: String?,
    val targetHost: String?,
    val reason: String
) : RuntimeException(
        if (fromHost == null) "Request to $targetHost refused: $reason" else "Redirect from $fromHost to $targetHost refused: $reason"
    )

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
 *
 * **The first request and every hop pass [guard] before they are sent** (#5444, #5448). A scraper reads detail and
 * pagination URLs from a venue's page, so the first URL is as untrusted as a `Location`. A URL to `169.254.169.254`,
 * to a host that resolves to `10.x`, or with a scheme other than `http` fails with [RequestRefusedException] and one
 * `WARN` line, and the target receives nothing, not even a `robots.txt` read. The guard resolves the host, so it runs
 * on [ioDispatcher] and not on the event loop.
 */
class RedirectFollowingFilter(
    private val guard: RedirectTargetGuard,
    private val ioDispatcher: CoroutineDispatcher
) : ExchangeFilterFunction {
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
        refuseIfGuarded(from = null, status = null, target = request.url())
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
            refuseIfGuarded(current, status, target)
            current = hop(current, status, target)
            hops++
        }
    }

    /** Refuses [target] when the guard does; [from] and [status] are null for the first request. */
    private suspend fun refuseIfGuarded(
        from: ClientRequest?,
        status: Int?,
        target: URI
    ) {
        val reason = withContext(ioDispatcher) { guard.reject(target.toString()) } ?: return
        val fromHost = from?.url()?.host
        logger.at(Level.WARN) {
            if (status == null) {
                message = "Refused a request to ${target.host}: $reason"
            } else {
                message = "Refused a $status redirect from $fromHost to ${target.host}: $reason"
                payload = mapOf(LogFields.HTTP_STATUS to status)
            }
        }
        throw RequestRefusedException(fromHost, target.host, reason)
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

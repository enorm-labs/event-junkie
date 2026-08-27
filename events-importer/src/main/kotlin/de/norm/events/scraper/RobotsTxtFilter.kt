package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.mono
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono

/**
 * Raised when a venue's `robots.txt` disallows the URL an importer asked for.
 *
 * A policy answer rather than a parse failure, which is why it carries its own
 * [scrapeFailureReason] tag: more code will not fix it, and the response is to change the URL or to
 * stop importing that venue.
 */
class RobotsDisallowedException(
    val url: String,
    val robotsTxtUrl: String?
) : RuntimeException("robots.txt disallows $url (rules from ${robotsTxtUrl ?: "none"})")

/**
 * WebClient [ExchangeFilterFunction] that checks every outbound scraper request against the target
 * host's `robots.txt`.
 *
 * **This is ADR-007 best-practice #1, moved from a habit into the code.** The rule always said to
 * check a venue's `robots.txt` before writing its importer. Nothing enforced it, and 3 of 80
 * importer packages record having done it. Registered here the check is transparent to scrapers,
 * exactly as [PerHostThrottlingFilter] is for rate limiting, so a new venue is covered by its
 * importer's first request rather than by somebody remembering.
 *
 * **It reports before it enforces.** [ScraperProperties.robotsEnforced] is `false` by default, and
 * in that state a disallowed request is logged and still sent. That is deliberate: no one knows how
 * many of the 80 configured hosts disallow the paths we already read, and turning enforcement on
 * blind could stop imports across the estate in one deploy. Phase 1 produces the evidence, and
 * enforcement is flipped on once that evidence has been read (#790).
 *
 * **Registration order matters.** This filter sits *before* [PerHostThrottlingFilter] on the
 * builder, so the `robots.txt` fetch that [RobotsRulesCache] performs travels through a client that
 * still throttles per host and still sends the identifying `User-Agent` — while never re-entering
 * this filter, because [RobotsRulesCache] holds the separate [SCRAPER_BASE_WEB_CLIENT]. Reversing the two
 * would leave the `robots.txt` fetches unthrottled.
 */
class RobotsTxtFilter(
    private val rulesCache: RobotsRulesCache,
    private val enforced: Boolean
) : ExchangeFilterFunction {
    private val logger = KotlinLogging.logger {}

    override fun filter(
        request: ClientRequest,
        next: ExchangeFunction
    ): Mono<ClientResponse> {
        val url = request.url().toString()

        return mono { rulesCache.check(url) }
            .flatMap { check ->
                if (check.allowed) {
                    next.exchange(request)
                } else if (enforced) {
                    logger.warn { "Blocked by robots.txt: $url" }
                    Mono.error(RobotsDisallowedException(url, check.robotsTxtUrl))
                } else {
                    // Report-only. The line is the finding; the request still goes out, because the
                    // alternative is discovering the blast radius in production.
                    logger.warn { "robots.txt disallows $url — sending it anyway (enforcement is off)" }
                    next.exchange(request)
                }
            }
    }
}

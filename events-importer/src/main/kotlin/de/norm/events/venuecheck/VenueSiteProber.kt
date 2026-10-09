package de.norm.events.venuecheck

import de.norm.events.scraper.LogFields
import de.norm.events.scraper.RobotsDisallowedException
import de.norm.events.scraper.SCRAPER_WEB_CLIENT
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.netty.channel.ConnectTimeoutException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitExchange
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import io.netty.handler.timeout.TimeoutException as NettyTimeoutException

/**
 * Asks whether a venue's own site still answers. One GET per URL through the scraper's client
 * ([SCRAPER_WEB_CLIENT]), so each request honours `robots.txt`, shares the venue's per-host throttle and sends
 * the identifying `User-Agent` (ADR-007). The body is released unread: only the status matters.
 *
 * A host in [ForbiddenHosts] is never contacted. A 403 or 429 is [SiteOutcome.SKIPPED], like a `robots.txt`
 * disallow: the server answered, but refused this client, so the answer says nothing about the venue.
 */
@Component
class VenueSiteProber(
    @Qualifier(SCRAPER_WEB_CLIENT) private val webClient: WebClient
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Probes every fetchable URL of one venue. The venue fails when any URL fails, because a dead programme
     * link is worth a review too; the first failure is the one reported. It is [SiteOutcome.OK] when at least
     * one answered and none failed, and [SiteOutcome.SKIPPED] when none answered or failed. A skip keeps the
     * status of the first 403 or 429, so the pass can log the refusal.
     */
    suspend fun probeVenue(urls: List<String?>): ProbeResult {
        val results =
            urls
                .filterNotNull()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { probe(it) }
        return results.firstOrNull { it.outcome.isFailure }
            ?: results.firstOrNull { it.outcome == SiteOutcome.OK }
            ?: results.firstOrNull { it.isRefused }
            ?: ProbeResult(url = null, outcome = SiteOutcome.SKIPPED)
    }

    /** Probes one URL. Never throws: a fault is an outcome. */
    @Suppress("TooGenericExceptionCaught") // Every transport fault is an outcome; one dead site must not stop the pass.
    suspend fun probe(url: String): ProbeResult {
        if (ForbiddenHosts.isForbidden(url)) return ProbeResult(url, SiteOutcome.SKIPPED)
        return try {
            val status =
                webClient
                    .get()
                    // A pre-built URI, so a percent-encoded path is not encoded twice into a 404.
                    .uri(URI.create(url))
                    .awaitExchange { response ->
                        response.releaseBody().awaitSingleOrNull()
                        response.statusCode().value()
                    }
            val outcome =
                when {
                    status < HTTP_ERROR_FROM -> SiteOutcome.OK

                    // Bot protection or a rate limit refused this client: no answer about the site (#2812).
                    status in REFUSED_STATUSES -> SiteOutcome.SKIPPED

                    else -> SiteOutcome.HTTP
                }
            logger.at(Level.DEBUG) {
                message = "Venue site probe: $outcome"
                payload = mapOf(LogFields.URL to url, LogFields.HTTP_STATUS to status)
            }
            ProbeResult(url, outcome, status)
        } catch (e: Exception) {
            classify(url, e)
        }
    }

    internal fun classify(
        url: String,
        error: Throwable
    ): ProbeResult {
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.toList()
        val robots = chain.filterIsInstance<RobotsDisallowedException>().firstOrNull()
        val result =
            when {
                // A 5xx on robots.txt is a server that does not answer, which is what the probe looks for.
                robots?.unreadableStatus != null && robots.unreadableStatus >= HTTP_SERVER_ERROR_FROM -> {
                    ProbeResult(url, SiteOutcome.HTTP, robots.unreadableStatus)
                }

                // The venue said no. That is an answer from a live server, not a failure.
                robots != null -> {
                    ProbeResult(url, SiteOutcome.SKIPPED)
                }

                chain.any { it is UnknownHostException } -> {
                    ProbeResult(url, SiteOutcome.DNS)
                }

                chain.any { it is SSLException } -> {
                    ProbeResult(url, SiteOutcome.TLS)
                }

                // Before ConnectException: Netty's ConnectTimeoutException extends it.
                chain.any { it is TimeoutException || it is NettyTimeoutException || it is ConnectTimeoutException } -> {
                    ProbeResult(url, SiteOutcome.TIMEOUT)
                }

                chain.any { it is ConnectException } -> {
                    ProbeResult(url, SiteOutcome.CONNECTION)
                }

                else -> {
                    ProbeResult(url, SiteOutcome.OTHER)
                }
            }
        logger.at(Level.DEBUG) {
            message = "Venue site probe: ${result.outcome} (${error.javaClass.simpleName})"
            payload = mapOf(LogFields.URL to url)
        }
        return result
    }

    companion object {
        /** Statuses that refuse this client and say nothing about the site: 403 Forbidden, 429 Too Many Requests. */
        val REFUSED_STATUSES = setOf(403, 429)

        private const val HTTP_ERROR_FROM = 400
        private const val HTTP_SERVER_ERROR_FROM = 500
    }
}

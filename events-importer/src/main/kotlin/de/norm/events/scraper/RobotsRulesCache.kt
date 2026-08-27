package de.norm.events.scraper

import crawlercommons.robots.SimpleRobotRules
import crawlercommons.robots.SimpleRobotRulesParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What a host's `robots.txt` says about one URL, and where the answer came from. */
data class RobotsCheck(
    /** The host the rules belong to. */
    val host: String,
    /** The `robots.txt` that answered, or `null` where the host serves none or could not be reached. */
    val robotsTxtUrl: String?,
    /** Whether this specific URL is permitted. */
    val allowed: Boolean,
    /** When the rules behind this answer were read. */
    val checkedAt: Instant
)

/**
 * Reads, parses and caches one `robots.txt` per host.
 *
 * **The generalisation ADR-007 best-practice #1 was missing.** That rule makes the check a manual
 * step before a new importer is written, and 3 of 80 importer packages record having done it. A
 * cache behind [RobotsTxtFilter] applies it to every request instead, so a new venue is covered by
 * its importer's first fetch rather than by somebody remembering.
 *
 * **Parsing is `crawler-commons`, not ours.** `robots.txt` reads like a two-line format and is not:
 * groups are selected by user-agent, `Allow` beats `Disallow` on longest match, `*` and `$` are
 * wildcards, and the status codes carry meaning of their own. A parser that is subtly wrong reports
 * compliance we do not have, which is worse than no parser at all.
 *
 * **The status rules are RFC 9309 §2.3.1**, and [SimpleRobotRulesParser.failedFetch] implements
 * them: a 4xx means no rules exist and everything is permitted, and a 5xx means the file exists but
 * could not be read, which is a complete disallow. **A transport failure is not a status.** A venue
 * whose server we cannot reach at all has told us nothing, so that case permits everything —
 * treating silence as a prohibition would stop imports on any network blip.
 */
@Component
class RobotsRulesCache(
    @Qualifier(SCRAPER_BASE_WEB_CLIENT) private val webClient: WebClient,
    private val scraperProperties: ScraperProperties,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}
    private val parser = SimpleRobotRulesParser()

    /**
     * Per-host state, kept for the application lifetime. The host set is bounded by the number of
     * configured venues, as it is in [PerHostThrottlingFilter].
     */
    private val hosts = ConcurrentHashMap<String, HostEntry>()

    /**
     * Returns what the host's rules say about [url], reading `robots.txt` on a cache miss.
     *
     * Safe to call for every request: the fetch happens once per host per
     * [ScraperProperties.robotsCacheTtl] and every later call is a map read.
     */
    suspend fun check(url: String): RobotsCheck {
        val uri = runCatching { URI.create(url) }.getOrNull()
        val host = uri?.host
        if (uri == null || host.isNullOrBlank()) {
            // Not a URL we can reason about. Nothing to obey, and nothing worth failing over.
            return RobotsCheck(host = "", robotsTxtUrl = null, allowed = true, checkedAt = now())
        }

        return runCatching {
            val cached = rulesFor(host, uri)
            RobotsCheck(
                host = host,
                robotsTxtUrl = cached.robotsTxtUrl,
                allowed = cached.rules.isAllowed(url),
                checkedAt = cached.fetchedAt
            )
        }.getOrElse { error ->
            // This never throws, and owning that promise here is what lets the import pipeline call
            // it while writing a source's final status — a path that must always commit.
            logger.warn(error) { "robots.txt check failed for $url — treating it as permitted" }
            RobotsCheck(host = host, robotsTxtUrl = null, allowed = true, checkedAt = now())
        }
    }

    /**
     * The cached rules for [host], refreshed when absent or expired.
     *
     * The per-host [Mutex] is what stops a venue's first import from fetching `robots.txt` once per
     * detail page. Those requests arrive together, and without the lock every one of them misses
     * the cache before the first fetch completes. The check inside the lock is not redundant: the
     * caller that waited needs the entry the winner just wrote.
     */
    private suspend fun rulesFor(
        host: String,
        uri: URI
    ): CachedRules {
        val entry = hosts.computeIfAbsent(host) { HostEntry() }
        entry.rules?.takeIf { it.isFresh() }?.let { return it }

        return entry.mutex.withLock {
            entry.rules?.takeIf { it.isFresh() }
                ?: fetch(host, uri).also { entry.rules = it }
        }
    }

    /** Fetches and parses the host's `robots.txt`, on the scheme and port the source itself uses. */
    private suspend fun fetch(
        host: String,
        uri: URI
    ): CachedRules {
        val scheme = if (uri.scheme == "http") "http" else "https"
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val robotsUrl = "$scheme://$host$port/robots.txt"

        return runCatching { fetchAndParse(robotsUrl, host) }
            .getOrElse { error ->
                logger.warn(error) { "Could not reach $robotsUrl — treating $host as unrestricted" }
                CachedRules(ALLOW_ALL, robotsTxtUrl = null, fetchedAt = now())
            }
    }

    private suspend fun fetchAndParse(
        robotsUrl: String,
        host: String
    ): CachedRules =
        webClient
            .get()
            .uri(URI.create(robotsUrl))
            .awaitExchange { response ->
                val status = response.statusCode().value()
                // Read the body either way: draining it is what releases the connection, and an
                // error page's bytes are simply discarded below.
                val body = response.awaitBodyOrNull<ByteArray>() ?: ByteArray(0)

                if (response.statusCode().isError) {
                    logger.info { "No usable robots.txt for $host (HTTP $status)" }
                    CachedRules(parser.failedFetch(status), robotsTxtUrl = null, fetchedAt = now())
                } else {
                    val contentType =
                        response
                            .headers()
                            .contentType()
                            .map { it.toString() }
                            .orElse(PLAIN_TEXT)
                    val rules = parser.parseContent(robotsUrl, body, contentType, ROBOT_NAMES)
                    logger.info { "Read robots.txt for $host (${body.size} bytes, allowAll=${rules.isAllowAll})" }
                    CachedRules(rules, robotsTxtUrl = robotsUrl, fetchedAt = now())
                }
            }

    private fun now(): Instant = Instant.now(clock)

    private fun CachedRules.isFresh(): Boolean = Duration.between(fetchedAt, now()) < scraperProperties.robotsCacheTtl

    companion object {
        /**
         * The product token out of the `User-Agent`, and only the token.
         *
         * Passing the whole `Mozilla/5.0 (compatible; EventJunkie/1.0; +…)` string matches no
         * group, so every venue's rules would fall through to `*` — a check that runs, passes, and
         * means nothing. `crawler-commons` lower-cases the names it compares.
         */
        val ROBOT_NAMES = listOf("eventjunkie")

        private const val PLAIN_TEXT = "text/plain"
        private val ALLOW_ALL = SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL)
    }
}

/** One host's parsed rules, and when they were read. */
internal data class CachedRules(
    val rules: SimpleRobotRules,
    val robotsTxtUrl: String?,
    val fetchedAt: Instant
)

/** Per-host cache slot: the rules, and the lock that stops a stampede of concurrent fetches. */
private class HostEntry {
    val mutex = Mutex()

    @Volatile
    var rules: CachedRules? = null
}

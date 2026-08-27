package de.norm.events.scraper

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration properties for the HTML scraping infrastructure.
 *
 * Bound to the `app.scraper` prefix in `application.yaml`. Controls timeouts
 * and rate limiting for outbound HTTP requests made by [HtmlFetcher].
 *
 * The response body size limit is configured separately via the standard
 * Spring Boot property `spring.http.codecs.max-in-memory-size` (applies globally
 * to all WebClient codecs).
 */
@ConfigurationProperties(prefix = "app.scraper")
data class ScraperProperties(
    /** Maximum time to wait for a server response before aborting the fetch. */
    val responseTimeout: Duration = Duration.ofSeconds(DEFAULT_RESPONSE_TIMEOUT_SECONDS),
    /** Politeness delay between consecutive detail page fetches to avoid overwhelming target servers. */
    val politeDelayMillis: Long = DEFAULT_POLITE_DELAY_MILLIS,
    /**
     * Whether a `robots.txt` disallow **blocks** the request, or only records it.
     *
     * `true`, so that honouring a venue's rules is the fail-safe: a deployment that configures
     * nothing still obeys them, and ignoring them takes an explicit decision somebody wrote down.
     *
     * **It costs nothing today and buys the day a venue changes its mind.** A full import of every
     * source found no disallowed URL, at a listing or at any detail page (#795). Enforcement is what
     * turns a venue adding a rule into a loud `robots_disallowed` failure, rather than into us
     * continuing to fetch what they now forbid.
     */
    val robotsEnforced: Boolean = true,
    /**
     * How long a parsed `robots.txt` is reused before it is read again.
     *
     * A day, to match the default `import_interval_minutes`: any shorter and a single import run
     * re-reads the file it just read, any longer and a venue's edit takes more than one cycle to
     * reach us.
     */
    val robotsCacheTtl: Duration = Duration.ofHours(DEFAULT_ROBOTS_CACHE_TTL_HOURS)
) {
    companion object {
        private const val DEFAULT_RESPONSE_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_POLITE_DELAY_MILLIS = 200L
        private const val DEFAULT_ROBOTS_CACHE_TTL_HOURS = 24L
    }
}

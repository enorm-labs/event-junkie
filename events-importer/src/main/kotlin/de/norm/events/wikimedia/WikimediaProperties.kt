package de.norm.events.wikimedia

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the Wikidata and Commons reads, bound to `app.wikimedia` in `application.yaml`.
 *
 * Wikimedia publishes no hard read limit but asks for a contact in the `User-Agent` and for
 * requests in series rather than in parallel; the pace here keeps both.
 */
@ConfigurationProperties(prefix = "app.wikimedia")
data class WikimediaProperties(
    /** Whether the picture is read at all. Off in tests, so nothing reaches the network. */
    val enabled: Boolean = true,
    val wikidataBaseUrl: String = "https://www.wikidata.org/w/api.php",
    val commonsBaseUrl: String = "https://commons.wikimedia.org/w/api.php",
    /** Minimum gap between two requests, process-wide. */
    val politeDelayMillis: Long = DEFAULT_POLITE_DELAY_MILLIS,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** The thumbnail width asked of Commons. Advisory: Commons rounds up to a cached bucket. */
    val thumbWidth: Int = DEFAULT_THUMB_WIDTH,
    /** The largest file the image fetcher reads, `images.fetch.max-bytes`; a larger original is refused here rather than there. */
    val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    companion object {
        private const val DEFAULT_POLITE_DELAY_MILLIS = 250L
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_THUMB_WIDTH = 1600
        private const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
    }
}

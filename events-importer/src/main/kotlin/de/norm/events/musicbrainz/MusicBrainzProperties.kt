package de.norm.events.musicbrainz

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the MusicBrainz lookup, bound to `app.musicbrainz` in `application.yaml`.
 *
 * The defaults are what MusicBrainz asks of a client: one request a second at most, and an agent
 * string that names the product and a contact. The pace is set a little slower than the limit,
 * because the limit is per source address and shared by every process behind it.
 */
@ConfigurationProperties(prefix = "app.musicbrainz")
data class MusicBrainzProperties(
    /** Whether the sweep runs at all. Off in tests, so nothing reaches the network. */
    val enabled: Boolean = true,
    /** The WS/2 root, with its trailing slash. */
    val baseUrl: String = "https://musicbrainz.org/ws/2/",
    /** Minimum gap between two requests. MusicBrainz refuses more than one a second per address. */
    val politeDelayMillis: Long = DEFAULT_POLITE_DELAY_MILLIS,
    /** How long one request may take before it is abandoned. */
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** The pause after the first 503; each further retry waits three times the last. Whole seconds, because the limit is enforced in them. */
    val backoff: Duration = Duration.ofSeconds(DEFAULT_BACKOFF_SECONDS),
    /** How many times one request is retried after a 503 before the row is given up on. */
    val retries: Int = DEFAULT_RETRIES,
    /** How many rows one sweep looks up at most — the backfill's slice, ~9 minutes at the polite pace. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    /** How many EXACT rows one sweep reads the entity of at most (step C): one request here and up to two at Wikimedia per row. */
    val enrichMaxPerRun: Int = DEFAULT_ENRICH_MAX_PER_RUN
) {
    companion object {
        private const val DEFAULT_POLITE_DELAY_MILLIS = 1_100L
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_BACKOFF_SECONDS = 5L
        private const val DEFAULT_RETRIES = 3
        private const val DEFAULT_MAX_PER_RUN = 500
        private const val DEFAULT_ENRICH_MAX_PER_RUN = 100
    }
}

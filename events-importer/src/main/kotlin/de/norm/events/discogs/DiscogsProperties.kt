package de.norm.events.discogs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the Discogs lookup, bound to `app.discogs` in `application.yaml` (#2026).
 *
 * Off unless both consumer credentials are set: they are the application's, from Discogs' developer
 * settings, and they raise the limit from 25 to 60 requests a minute per source address. The OAuth
 * flow is not used, because the importer acts for no Discogs user.
 */
@ConfigurationProperties(prefix = "app.discogs")
data class DiscogsProperties(
    /** Whether the sweep runs at all. It also needs both credentials; see [active]. */
    val enabled: Boolean = true,
    /** The API root, with its trailing slash. */
    val baseUrl: String = "https://api.discogs.com/",
    /** The consumer key of the Discogs application. Never logged. */
    val consumerKey: String = "",
    /** The consumer secret of the Discogs application. Never logged. */
    val consumerSecret: String = "",
    /** Minimum gap between two requests: a little slower than 60 a minute, because the window is rolling. */
    val politeDelayMillis: Long = DEFAULT_POLITE_DELAY_MILLIS,
    /** How long one request may take before it is abandoned. */
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** The pause after a 429, or when the window is nearly spent. */
    val backoff: Duration = Duration.ofSeconds(DEFAULT_BACKOFF_SECONDS),
    /** How many times one request is retried after a 429 before the row is given up on. */
    val retries: Int = DEFAULT_RETRIES,
    /** How many rows one sweep looks up at most, about two minutes at the polite pace. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN
) {
    /** Whether a sweep may send anything: switched on, and both credentials present. */
    val active: Boolean get() = enabled && consumerKey.isNotBlank() && consumerSecret.isNotBlank()

    /** Keeps the credentials out of every log line and exception message that prints the properties. */
    override fun toString(): String =
        "DiscogsProperties(enabled=$enabled, baseUrl=$baseUrl, credentials=${if (consumerKey.isBlank() || consumerSecret.isBlank()) "missing" else "set"}, " +
            "politeDelayMillis=$politeDelayMillis, timeout=$timeout, backoff=$backoff, retries=$retries, maxPerRun=$maxPerRun)"

    companion object {
        private const val DEFAULT_POLITE_DELAY_MILLIS = 1_100L
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_BACKOFF_SECONDS = 20L
        private const val DEFAULT_RETRIES = 3
        private const val DEFAULT_MAX_PER_RUN = 100
    }
}

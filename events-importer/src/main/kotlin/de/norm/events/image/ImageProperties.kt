package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How the image cache fetches, and the limits it refuses to fetch past.
 *
 * The caps are security controls rather than tuning (ADR-019 §4). A URL taken out of scraped HTML
 * is attacker-influenced, and the bytes behind it are untrusted.
 */
@ConfigurationProperties(prefix = "app.images")
data class ImageProperties(
    /**
     * Whether the importer fetches images at all.
     *
     * **Off by default, and this is not a hotlink-versus-cache switch** (ADR-019 §2.10). Off means
     * nothing is fetched and nothing is stored, which is what CI and a local run without Docker
     * need. It can never cause a venue URL to reach a browser, because the BFF has no such flag and
     * serves only what storage holds.
     */
    val fetchEnabled: Boolean = false,
    /**
     * Largest image we will download.
     *
     * A cap rather than a preference: without one, the response body is whatever the far end sends.
     * Eight megabytes is generous for a poster and small enough to bound the damage.
     */
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    /**
     * Largest image we will accept, in pixels.
     *
     * Read from the file header without decoding the pixels, so a decompression bomb is rejected
     * before anything allocates for it. A 100-megapixel JPEG is a few hundred kilobytes on the wire.
     */
    val maxPixels: Long = DEFAULT_MAX_PIXELS,
    /** How long a successful fetch is trusted before the URL is checked again. */
    val refreshAfter: Duration = Duration.ofDays(DEFAULT_REFRESH_DAYS),
    /**
     * How long a failed fetch is remembered before it is retried.
     *
     * The negative cache (ADR-019 §3.6). The import runs daily, so without this a dead URL is
     * requested every night forever — load on a venue that returns nothing.
     */
    val retryFailedAfter: Duration = Duration.ofDays(DEFAULT_RETRY_FAILED_DAYS),
    /** How many URLs one scheduled pass takes on, so a first run does not fetch the whole corpus at once. */
    val batchSize: Int = DEFAULT_BATCH_SIZE
) {
    companion object {
        private const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_MAX_PIXELS = 50L * 1000 * 1000
        private const val DEFAULT_REFRESH_DAYS = 30L
        private const val DEFAULT_RETRY_FAILED_DAYS = 7L
        private const val DEFAULT_BATCH_SIZE = 200
    }
}

package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * Whether the API hands out our own copy of a venue image, and where it reads the bytes from.
 * [enabled] is the switch ADR-019 comes down to: off, a response carries the venue's URL; on, a
 * path on our own origin, and an image with no derivative yet is reported absent rather than
 * hotlinked. So it must not be turned on before the derivatives exist, or the backlog shows as
 * blank cards (`docs/ops/PLATFORM_SETUP.md` records the order).
 *
 * The storage half repeats the importer's [ConfigurationProperties] under the same environment
 * variable names, so one Secret serves both; not shared because `events-core` has no S3
 * dependency and should not gain one to save eight lines.
 */
@ConfigurationProperties(prefix = "app.images")
data class ImageServingProperties(
    val serving: Serving = Serving(),
    val storage: Storage = Storage(),
    val cache: Cache = Cache()
) {
    data class Serving(
        val enabled: Boolean = false
    )

    /**
     * How much of the bucket this process keeps in memory (#847). A cache, not a store: eviction
     * costs latency only, and content-addressed keys make an entry safe to keep with no expiry.
     */
    data class Cache(
        /**
         * The ceiling, in bytes of image data. The default holds one card-sized variant for every image
         * staging has: 1,578 originals at roughly 20 kB for a 192 or 288 pixel derivative fit in 32 MB;
         * detail-page variants evict cards, the right trade. The 20 kB is estimated;
         * `bff_images_cache_weight` reports the bytes held and `cache_gets{cache="images"}` the hit
         * ratio. Heap, inside `bff.maxRamPercentage`.
         *
         * One word rather than `maxSize`, because Boot maps a dashed name by removing the dash:
         * `max-size` needs `APP_IMAGES_CACHE_MAXSIZE`, and the obvious spelling binds to nothing.
         */
        val size: DataSize = DEFAULT_SIZE
    ) {
        companion object {
            val DEFAULT_SIZE: DataSize = DataSize.ofMegabytes(32)
        }
    }

    data class Storage(
        val endpoint: String = "https://fsn1.your-objectstorage.com",
        /**
         * Region for request signing. Hetzner enforces it in the signature and rejects a mismatch with
         * an error that reads like bad credentials.
         */
        val region: String = "fsn1",
        val bucket: String = "event-junkie-images",
        val accessKey: String = "",
        val secretKey: String = ""
    ) {
        /**
         * Whether credentials were supplied. No environment prefix here: a served key is read from the
         * row that recorded it.
         */
        fun isConfigured(): Boolean = accessKey.isNotBlank() && secretKey.isNotBlank()
    }
}

package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * Whether the API hands out our own copy of a venue image, and where it reads the bytes from.
 *
 * **[enabled] is the switch the whole of ADR-019 comes down to.** Off, a response carries the
 * venue's URL and the visitor's browser fetches from the venue, which is what the site does today.
 * On, a response carries a path on our own origin, and an image with no derivative yet is reported
 * as absent rather than hotlinked — because falling back to the venue would keep the disclosure that
 * this exists to remove.
 *
 * **So it must not be turned on before the derivatives exist.** An environment that enables it while
 * the imgproxy pass is still working through the backlog shows blank cards for whatever is left.
 * `docs/ops/PLATFORM_SETUP.md` records the order.
 *
 * The storage half repeats the importer's [ConfigurationProperties] rather than sharing them.
 * The two apps read the same bucket under the same environment variable names, which is what lets
 * one Secret serve both; the class is not shared because `events-core` has no S3 dependency and
 * should not gain one to save eight lines.
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
     * How much of the bucket this process keeps in memory (#847).
     *
     * **A cache and not a store.** Every entry can be read again from the bucket, so eviction costs
     * latency and nothing else. The keys are content addressed, which is what makes an entry safe to
     * keep with no expiry: one key can only ever mean one file.
     */
    data class Cache(
        /**
         * The ceiling, counted in bytes of image data rather than entries.
         *
         * **The default holds one card-sized variant for every image staging has.** That is 1,578
         * originals at roughly 20 kB for a 192 or 288 pixel derivative, so the cards fit in 32 MB.
         * Detail-page variants are several times larger and evict cards, which is the right trade.
         * A card list draws many images at once. A detail page draws one.
         *
         * The 20 kB is the one input estimated rather than measured. `bff_images_cache_weight` reports
         * the bytes actually held, and `cache_gets{cache="images"}` the hit ratio to raise it
         * against. It is heap, inside `bff.maxRamPercentage` of the container limit.
         *
         * **One word rather than `maxSize`, because the chart sets it from the environment.** Boot
         * maps a dashed name by removing the dash. So `max-size` needs `APP_IMAGES_CACHE_MAXSIZE`,
         * and the obvious spelling binds to nothing while the pod starts.
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
         * Region for request signing.
         *
         * **Hetzner enforces it in the signature and rejects a mismatch**, with an error that reads
         * like bad credentials rather than a wrong region.
         */
        val region: String = "fsn1",
        val bucket: String = "event-junkie-images",
        val accessKey: String = "",
        val secretKey: String = ""
    ) {
        /**
         * Whether credentials were supplied.
         *
         * There is no environment prefix here, unlike the importer's. A served key is read from the
         * row that recorded it, so this side never builds a key and cannot build one for the wrong
         * environment.
         */
        fun isConfigured(): Boolean = accessKey.isNotBlank() && secretKey.isNotBlank()
    }
}

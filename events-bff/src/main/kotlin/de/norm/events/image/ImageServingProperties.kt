package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties

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
    val storage: Storage = Storage()
) {
    data class Serving(
        val enabled: Boolean = false
    )

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

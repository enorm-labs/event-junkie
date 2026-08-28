package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where cached images are written, and under whose name.
 *
 * Hetzner Object Storage is S3-compatible, so this is an endpoint and a region rather than a
 * provider choice (ADR-012 put us on Hetzner; ADR-019 §2.1 chose a bucket over the database).
 */
@ConfigurationProperties(prefix = "app.images.storage")
data class ImageStorageProperties(
    /** Host and scheme, unlike the OpenTofu variable of the same name, which takes `host[:port]`. */
    val endpoint: String = "https://fsn1.your-objectstorage.com",
    /**
     * Region for request signing.
     *
     * **Hetzner enforces it in the signature and rejects a mismatch**, with an error that reads like
     * bad credentials rather than a wrong region. `infra/bootstrap/variables.tf` carries the same
     * warning for the same reason.
     */
    val region: String = "fsn1",
    val bucket: String = "event-junkie-images",
    /**
     * The key prefix this environment owns, and the one thing here that must never be shared.
     *
     * **Keys are content addressed, so staging and production compute the same key for the same
     * venue image.** One bucket serves both (ADR-019 §2.8), so without a prefix an orphan sweep
     * would ask its own database about a key the *other* environment still serves, read no, and
     * delete it. That is #270's shape, one bucket over.
     */
    val prefix: String = "local",
    val accessKey: String = "",
    val secretKey: String = ""
) {
    /** Whether credentials were supplied. Without them the client is not built and nothing stores. */
    fun isConfigured(): Boolean = accessKey.isNotBlank() && secretKey.isNotBlank()
}

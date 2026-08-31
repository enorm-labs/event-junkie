package de.norm.events.image

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How derivatives are generated, and at which sizes.
 *
 * imgproxy runs as a sidecar and is called during an import. It never serves a visitor
 * ([ADR-020](../../../../../../../docs/adr/ADR-020_IMAGE_PROCESSING.md)), so every derivative is a
 * content-addressed immutable object that any dumb server can send with a one-year cache header.
 */
@ConfigurationProperties(prefix = "app.images.imgproxy")
data class ImgproxyProperties(
    /** Off means originals are stored and no derivative is generated. */
    val enabled: Boolean = false,
    /** The sidecar, on loopback. Nothing outside the pod reaches it. */
    val baseUrl: String = "http://127.0.0.1:8080",
    /** Hex-encoded, as imgproxy requires. Empty disables signing, which imgproxy allows and we do not. */
    val key: String = "",
    val salt: String = "",
    /**
     * The widths to generate, in CSS pixels of the largest rendering.
     *
     * **Derived from what the site actually renders, which is not what an earlier draft assumed.**
     * `EventCard` and `VenueCard` draw at 80 px and `BaseDetailView` at 96 px, so 192 and 288 cover
     * those at 2x and 3x. `EventDetailView` draws the image at the full width of a `max-w-3xl`
     * column — 704 px after padding — which 768 covers with headroom and 1536 covers at 2x
     * ([#804](https://github.com/enorm-labs/event-junkie/issues/804) is why that site is on the list
     * at all).
     */
    val widths: List<Int> = DEFAULT_WIDTHS,
    /**
     * The formats to write, best first.
     *
     * Chosen with `<picture>` rather than the `Accept` header. Negotiating on `Accept` returns
     * different bytes from one URL, which is the opposite of the immutable key this design rests on.
     */
    val formats: List<String> = DEFAULT_FORMATS
) {
    fun isSigned(): Boolean = key.isNotBlank() && salt.isNotBlank()

    /**
     * How many files one original should end up with.
     *
     * Here rather than in [ImageDerivativeService] because two callers need it: the pass that
     * generates the missing ones, and the gauge that counts what is still missing. Two copies of
     * this product would let a backlog read as empty while the pass still had work.
     */
    val expectedVariants: Int get() = widths.size * formats.size

    companion object {
        /** 96 px at 2x and 3x for the cards, then the detail column at 1x and 2x. */
        val DEFAULT_WIDTHS = listOf(192, 288, 768, 1536)
        val DEFAULT_FORMATS = listOf("avif", "webp", "jpg")
    }
}

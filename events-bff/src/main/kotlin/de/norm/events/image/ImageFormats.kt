package de.norm.events.image

/**
 * The formats a derivative exists in, and the order a browser should be offered them.
 *
 * One list, read by the serving route's allow-list and by the `<picture>` sources it hands out. Two
 * lists would let a format be offered that the route refuses, which is a broken image rather than a
 * failed build.
 *
 * **Chosen with `<picture>` rather than the `Accept` header** (ADR-020). Negotiating on `Accept`
 * returns different bytes from one URL, which is the opposite of the immutable key this design rests
 * on.
 */
object ImageFormats {
    /**
     * The format a bare `imageUrl` names, and the last `<source>` offered.
     *
     * JPEG, because it is the one every browser reads. An image with no JPEG derivative is treated
     * as not servable rather than served in a format something might not decode.
     */
    const val FALLBACK = "jpg"

    /** Best first, which is the order `<picture>` requires: the browser takes the first it supports. */
    val ORDERED = listOf("avif", "webp", FALLBACK)

    /**
     * The media type each format is sent and offered as.
     *
     * `image/jpg` is not a media type. It has to be `image/jpeg`, and deriving it as `"image/$format"`
     * would get that one wrong.
     */
    val MEDIA_TYPES = mapOf("avif" to "image/avif", "webp" to "image/webp", FALLBACK to "image/jpeg")

    fun mediaType(format: String): String? = MEDIA_TYPES[format]
}

package de.norm.events.image

/**
 * The formats a derivative exists in, and the order a browser is offered them. One list read by
 * the serving route's allow-list and the `<picture>` sources, so a format cannot be offered and
 * refused. Chosen with `<picture>` rather than the `Accept` header (ADR-020): negotiating on
 * `Accept` returns different bytes from one URL, the opposite of the immutable key.
 */
object ImageFormats {
    /**
     * The format a bare `imageUrl` names and the last `<source>` offered: JPEG, the one every
     * browser reads. An image with no JPEG derivative is not servable.
     */
    const val FALLBACK = "jpg"

    /** Best first, which is the order `<picture>` requires: the browser takes the first it supports. */
    val ORDERED = listOf("avif", "webp", FALLBACK)

    /**
     * The media type each format is sent as. `image/jpg` is not a media type; deriving
     * `"image/$format"` would get that one wrong.
     */
    val MEDIA_TYPES = mapOf("avif" to "image/avif", "webp" to "image/webp", FALLBACK to "image/jpeg")

    fun mediaType(format: String): String? = MEDIA_TYPES[format]
}

package de.norm.events.image

import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Answers, for a page of events at once, which venue images we can serve ourselves.
 *
 * Modelled on [de.norm.events.sourcelicence.SourceLicenceGate], and for the same reason: it returns
 * the answers rather than applying them, so the caller keeps ownership of the types it rewrites and
 * the module edge stays one-way.
 */
@Service
class CachedImageGate(
    private val repository: CachedImageRepository,
    private val properties: ImageServingProperties,
    /**
     * The prefix Spring itself serves this application under.
     *
     * Read from the framework's own property rather than configured a second time, so the URL this
     * hands out and the path [CachedImageController] is mapped at cannot disagree. In a cluster it is
     * `/api`; locally and in the tests it is empty, and both are correct.
     */
    @Value("\${spring.webflux.base-path:}") private val basePath: String
) {
    /**
     * Resolves [sourceUrls] to what can be served for each.
     *
     * Returns the disabled answer without querying while serving is off. That keeps the switch a
     * single decision rather than one every caller has to remember, and it means the tables can fill
     * up on an environment that is not serving yet at no cost per request.
     */
    suspend fun forUrls(sourceUrls: Collection<String?>): CachedImages {
        if (!properties.serving.enabled) return CachedImages.disabled()
        val distinct = sourceUrls.filterNotNull().distinct()
        return CachedImages(if (distinct.isEmpty()) emptyMap() else lookUp(distinct), "$basePath$IMAGES_PATH")
    }

    private suspend fun lookUp(sourceUrls: List<String>): Map<String, ServableImage> =
        repository
            .findServableBySourceUrlIn(sourceUrls)
            .toList()
            .filter { it.format == SERVED_FORMAT }
            .groupBy { it.sourceUrl }
            .mapValues { (_, variants) ->
                ServableImage(contentHash = variants.first().contentHash, widths = variants.map { it.width }.toSortedSet())
            }

    companion object {
        /**
         * The one format a bare `imageUrl` may name.
         *
         * JPEG, because a single URL in an `<img src>` cannot negotiate and every browser reads it.
         * The AVIF and WebP derivatives are generated and stored, and they reach a visitor through a
         * `<picture>` element rather than through this field.
         */
        const val SERVED_FORMAT = "jpg"
    }
}

/**
 * What is servable for one page's worth of venue image URLs, and the rule for using it.
 *
 * A value rather than a map of strings, because the answer depends on the size being rendered: the
 * same image is a 288 px card and a 768 px detail header, and picking one width for both would give
 * either a blurred detail page or a card list that downloads ten times what it draws.
 */
class CachedImages private constructor(
    private val byUrl: Map<String, ServableImage>,
    private val urlPrefix: String,
    private val serving: Boolean
) {
    constructor(byUrl: Map<String, ServableImage>, urlPrefix: String) : this(byUrl, urlPrefix, serving = true)

    /**
     * What the API should report for [sourceUrl] when the image is drawn at [renderedWidth].
     *
     * **The whole of ADR-019's decision is these three lines.** While serving is off the venue's own
     * URL is returned unchanged, which is what the site does today. While it is on, an image we hold
     * becomes a path on our own origin, and one we do not hold becomes null — reported as absent
     * rather than hotlinked, because falling back to the venue would reinstate exactly the disclosure
     * that caching exists to remove ([#792](https://github.com/enorm-labs/event-junkie/issues/792)).
     */
    fun rewrite(
        sourceUrl: String?,
        renderedWidth: Int
    ): String? {
        if (!serving) return sourceUrl
        val image = sourceUrl?.let { byUrl[it] }
        return image?.widthFor(renderedWidth)?.let { "$urlPrefix/${image.contentHash}/$it.${CachedImageGate.SERVED_FORMAT}" }
    }

    companion object {
        /** The answer while `app.images.serving.enabled` is false: leave every URL as it was. */
        fun disabled(): CachedImages = CachedImages(emptyMap(), "", serving = false)
    }
}

/** One image we hold, and the widths it exists at. */
data class ServableImage(
    val contentHash: String,
    val widths: Set<Int>
) {
    /**
     * The narrowest width that is at least [minimumWidth], or the widest we have.
     *
     * Falling back to the widest rather than to null: an image whose largest derivative is narrower
     * than the slot is upscaled by the browser and looks soft. Refusing to show it at all would look
     * like the image is missing, and it is not.
     */
    fun widthFor(minimumWidth: Int): Int? = widths.firstOrNull { it >= minimumWidth } ?: widths.maxOrNull()
}

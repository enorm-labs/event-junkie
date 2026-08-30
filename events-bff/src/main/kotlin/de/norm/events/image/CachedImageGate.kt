package de.norm.events.image

import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.util.SortedSet

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
    private val properties: ImageServingProperties
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
        return CachedImages(if (distinct.isEmpty()) emptyMap() else lookUp(distinct), IMAGES_PATH)
    }

    private suspend fun lookUp(sourceUrls: List<String>): Map<String, ServableImage> =
        repository
            .findServableBySourceUrlIn(sourceUrls)
            .toList()
            .groupBy { it.sourceUrl }
            .mapValues { (_, variants) ->
                ServableImage(
                    contentHash = variants.first().contentHash,
                    widthsByFormat = variants.groupBy { it.format }.mapValues { (_, rows) -> rows.map { it.width }.toSortedSet() },
                    intrinsicWidth = variants.first().intrinsicWidth,
                    intrinsicHeight = variants.first().intrinsicHeight
                )
            }
}

/**
 * What is servable for one page's worth of venue image URLs, and the rule for using it.
 *
 * A value rather than a map of strings, because the answer depends on the size being rendered: the
 * same image is a 96 px card and a 704 px detail header, and one width for both would give either a
 * blurred detail page or a card list that downloads ten times what it draws.
 */
class CachedImages private constructor(
    private val byUrl: Map<String, ServableImage>,
    private val urlPrefix: String,
    private val serving: Boolean
) {
    constructor(byUrl: Map<String, ServableImage>, urlPrefix: String) : this(byUrl, urlPrefix, serving = true)

    /**
     * What the API should report for [sourceUrl] when the image is drawn [renderedWidth] CSS pixels wide.
     *
     * **The whole of ADR-019's decision is these three lines.** While serving is off the venue's own
     * URL is returned unchanged, which is what the site does today. While it is on, an image we hold
     * becomes a set of URLs on our own origin, and one we do not hold becomes null — reported as
     * absent rather than hotlinked, because falling back to the venue would reinstate exactly the
     * disclosure that caching exists to remove
     * ([#792](https://github.com/enorm-labs/event-junkie/issues/792)).
     */
    fun serve(
        sourceUrl: String?,
        renderedWidth: Int
    ): ServedImage {
        if (!serving) return ServedImage(sourceUrl, emptyList())
        val image = sourceUrl?.let { byUrl[it] }
        return image?.serve(urlPrefix, renderedWidth) ?: ServedImage.ABSENT
    }

    companion object {
        /** The answer while `app.images.serving.enabled` is false: leave every URL as it was. */
        fun disabled(): CachedImages = CachedImages(emptyMap(), "", serving = false)
    }
}

/** One image we hold, its shape, and the widths it exists at in each format. */
data class ServableImage(
    val contentHash: String,
    val widthsByFormat: Map<String, SortedSet<Int>>,
    /** Null by default, because null is what most rows carry — see [ServableVariant.intrinsicWidth]. */
    val intrinsicWidth: Int? = null,
    val intrinsicHeight: Int? = null
) {
    /**
     * The URLs to offer for a slot [renderedWidth] CSS pixels wide.
     *
     * Empty unless a JPEG derivative exists. JPEG is the one format every browser reads, so an image
     * without it has no safe `<img src>` — and offering only AVIF and WebP would be a blank space on
     * anything that cannot decode them.
     */
    fun serve(
        urlPrefix: String,
        renderedWidth: Int
    ): ServedImage {
        val widths = candidateWidths(renderedWidth)
        val fallback = widthsByFormat[ImageFormats.FALLBACK].orEmpty().filter { it in widths }
        return if (fallback.isEmpty()) {
            ServedImage.ABSENT
        } else {
            ServedImage(
                url = url(urlPrefix, fallback.first(), ImageFormats.FALLBACK),
                sources = ImageFormats.ORDERED.mapNotNull { source(urlPrefix, it, widths) },
                // The original's dimensions rather than the derivative's, because what the browser
                // takes from them is the ratio, and imgproxy resizes on width alone.
                intrinsicWidth = intrinsicWidth.takeIf { hasBothDimensions },
                intrinsicHeight = intrinsicHeight.takeIf { hasBothDimensions }
            )
        }
    }

    /**
     * Whether this image can report a shape at all.
     *
     * **Dropped to neither rather than reported as one**, and normalised here rather than asserted
     * in [ServedImage], because a single odd row must not fail a page of twenty events. The two
     * columns are written together, so this is a guard and not an expected state.
     */
    private val hasBothDimensions: Boolean get() = intrinsicWidth != null && intrinsicHeight != null

    /**
     * The generated widths worth offering for a slot [renderedWidth] CSS pixels wide.
     *
     * From the slot itself up to three times it, because a device pixel ratio above 3 is rarer than
     * the bytes it would cost every other device. Narrower than the slot is upscaling the browser
     * would have to do anyway, and wider is a file nothing will draw.
     *
     * Falls back to the widest we hold when the band is empty. Soft rather than absent: an image
     * whose largest derivative is narrower than the slot still shows, and refusing it would look like
     * the image is missing.
     */
    private fun candidateWidths(renderedWidth: Int): Set<Int> {
        val all = widthsByFormat.values.flatten().toSortedSet()
        return all.filterTo(sortedSetOf()) { it in renderedWidth..(renderedWidth * MAX_PIXEL_RATIO) }.ifEmpty {
            listOfNotNull(all.maxOrNull()).toSortedSet()
        }
    }

    /** One `<source>`, or null when this format has no derivative among [widths]. */
    private fun source(
        urlPrefix: String,
        format: String,
        widths: Set<Int>
    ): ImageSourceResponse? {
        val available = widthsByFormat[format].orEmpty().filter { it in widths }
        val mediaType = ImageFormats.mediaType(format)
        return if (available.isEmpty() || mediaType == null) {
            null
        } else {
            ImageSourceResponse(
                type = mediaType,
                srcset = available.joinToString(", ") { "${url(urlPrefix, it, format)} ${it}w" }
            )
        }
    }

    private fun url(
        urlPrefix: String,
        width: Int,
        format: String
    ) = "$urlPrefix/$contentHash/$width.$format"

    private companion object {
        const val MAX_PIXEL_RATIO = 3
    }
}

/**
 * What one image field carries: a URL to put in `src`, and the formats to offer above it.
 *
 * Both together rather than two independent lookups, so a caller cannot fill one field and forget
 * the other — which would be a `<picture>` that always falls through to JPEG, and nothing would fail.
 */
data class ServedImage(
    val url: String?,
    val sources: List<ImageSourceResponse>,
    /**
     * The image's own pixel dimensions, or null.
     *
     * **Both or neither**, which [ServableImage] guarantees. They reserve the space a lazy image
     * will occupy, and a browser given one of the pair reserves nothing — so half an answer is the
     * same layout shift as no answer, arrived at less obviously (#848).
     */
    val intrinsicWidth: Int? = null,
    val intrinsicHeight: Int? = null
) {
    companion object {
        /** Nothing to serve: the image is reported absent rather than fetched from the venue. */
        val ABSENT = ServedImage(null, emptyList())
    }
}

/** One format of a cached image, shaped as the `<source>` element that selects it. */
@Schema(description = "One format of a cached image, for a <source> element inside <picture>")
data class ImageSourceResponse(
    @Schema(description = "Media type for the `type` attribute", example = "image/avif")
    val type: String,
    @Schema(
        description = "Candidate URLs and their widths, for the `srcset` attribute",
        example = "/api/images/0f4b…/192.avif 192w, /api/images/0f4b…/288.avif 288w"
    )
    val srcset: String
)

package de.norm.events.image

import de.norm.events.scraper.SCRAPER_WEB_CLIENT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Downloads one venue image and describes it, without keeping the bytes. Uses the scraper's
 * throttled client ([SCRAPER_WEB_CLIENT]), so an image fetch obeys `robots.txt` and shares the
 * venue's per-host timer (ADR-007). Returns a description, not a file; the hash is computed here
 * so two events sharing one poster converge on one object.
 */
@Component
class ImageFetcher(
    @Qualifier(SCRAPER_WEB_CLIENT) private val webClient: WebClient,
    @Qualifier("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
    private val validator: ImageUrlValidator,
    private val properties: ImageProperties
) {
    private val logger = KotlinLogging.logger {}

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null
    ): ImageFetchResult {
        val target = url.encodeLiteralSpaces()
        validator.reject(target)?.let { return ImageFetchResult.Rejected(it) }

        return try {
            webClient
                .get()
                // A pre-built URI, so WebClient uses the percent-encoded URL verbatim; a String is re-encoded,
                // turning `%3A` into `%253A`. This class reintroduced the bug: 22 of Frannz Club's images are
                // proxied through `images.copilot.events/resize?url=…`, and every one came back 400.
                .uri(URI.create(target))
                .apply {
                    etag?.let { header(HttpHeaders.IF_NONE_MATCH, it) }
                    lastModified?.let { header(HttpHeaders.IF_MODIFIED_SINCE, it) }
                }.awaitExchange { response ->
                    when {
                        response.statusCode() == HttpStatus.NOT_MODIFIED -> {
                            ImageFetchResult.NotModified
                        }

                        !response.statusCode().is2xxSuccessful -> {
                            ImageFetchResult.Rejected("HTTP ${response.statusCode().value()}")
                        }

                        // Declared too large: refuse before reading a byte. The header can be wrong, so `describe` still
                        // measures what arrives.
                        response.headers().contentLength().orElse(0) > properties.maxBytes -> {
                            ImageFetchResult.Rejected("declared larger than ${properties.maxBytes} bytes")
                        }

                        else -> {
                            describe(
                                bytes = response.awaitBody<ByteArray>(),
                                etag = response.headers().header(HttpHeaders.ETAG).firstOrNull(),
                                lastModified = response.headers().header(HttpHeaders.LAST_MODIFIED).firstOrNull()
                            )
                        }
                    }
                }
        } catch (
            // Every transport fault, deliberately: a reset, a DNS failure and a read timeout mean the same
            // to the caller. Nothing is rethrown, so one dead URL cannot stop the pass.
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            // The reason is logged and never reaches a metric tag: unbounded cardinality.
            logger.debug(e) { "Image fetch failed for $target" }
            // The codec's own limit fires before `describe` sees the bytes, so without this an oversized
            // image is recorded as a transport fault, and an operator chases the network.
            if (e.isBufferLimit()) {
                ImageFetchResult.Rejected("larger than the ${properties.maxBytes} byte buffer limit")
            } else {
                ImageFetchResult.Rejected("fetch failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Applies the size, type and pixel limits. One guard clause per limit, over the return-count
     * rule, so each refusal reason stays legible to the operator who reads it.
     */
    @Suppress("ReturnCount")
    private suspend fun describe(
        bytes: ByteArray,
        etag: String?,
        lastModified: String?
    ): ImageFetchResult {
        if (bytes.size > properties.maxBytes) return ImageFetchResult.Rejected("larger than ${properties.maxBytes} bytes")

        val contentType = sniff(bytes) ?: return ImageFetchResult.Rejected("not an allowed image type")

        val dimensions = withContext(ioDispatcher) { readDimensions(bytes) }
        // A type this JVM can read, that it then cannot read, is a corrupt file. A type it has no reader
        // for is not evidence of anything.
        if (dimensions == null && contentType in MEASURABLE_TYPES) return ImageFetchResult.Rejected("unreadable image header")
        if (dimensions != null && dimensions.first.toLong() * dimensions.second > properties.maxPixels) {
            return ImageFetchResult.Rejected("larger than ${properties.maxPixels} pixels")
        }

        return ImageFetchResult.Success(
            bytes = bytes,
            contentHash = sha256(bytes),
            contentType = contentType,
            byteSize = bytes.size.toLong(),
            width = dimensions?.first,
            height = dimensions?.second,
            etag = etag,
            lastModified = lastModified
        )
    }

    /**
     * Identifies the file from its own first bytes, never from `Content-Type`, which the venue
     * controls: an SVG served from our origin executes script in our origin (ADR-019 §4). Anything
     * not on this list is refused, SVG included. This list is the control, not imgproxy, which reads
     * SVG as a source; it reads all five of these too, which is why WebP and AVIF belong here.
     */
    private fun sniff(bytes: ByteArray): String? =
        when {
            bytes.startsWith(JPEG_MAGIC) -> "image/jpeg"
            bytes.startsWith(PNG_MAGIC) -> "image/png"
            bytes.startsWith(GIF_MAGIC) -> "image/gif"
            bytes.isContainer(container = "RIFF", brand = "WEBP") -> "image/webp"
            bytes.isContainer(container = "ftyp", brand = "avif") -> "image/avif"
            else -> null
        }

    /**
     * Width and height from the file header, without decoding: `getWidth` reads only as far as the
     * header, so a decompression bomb is measured rather than allocated for. Null for WebP and AVIF,
     * not a failure: a stock JDK has no reader, and adding one would decode untrusted bytes inside
     * this process, which ADR-020 moved out. imgproxy reports the real dimensions.
     */
    private fun readDimensions(bytes: ByteArray): Pair<Int, Int>? =
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } catch (_: Exception) {
                null
            } finally {
                reader.dispose()
            }
        }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Percent-encodes literal spaces, and nothing else. A space is never legal in a URI, so encoding
     * one cannot double-encode: nineteen Wild at Heart images are `R1783504681V8 Wankers.jpeg`,
     * which a browser fetches and `URI` rejects. Other forbidden characters have not been seen in
     * this corpus, and a general re-encoder would undo an already-escaped URL.
     */
    private fun String.encodeLiteralSpaces(): String = replace(" ", "%20")

    /** Whether this fault, or anything under it, is the codec refusing an oversized body. */
    private fun Throwable.isBufferLimit(): Boolean = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.any { it is DataBufferLimitException }

    /**
     * Whether these bytes open with [prefix], as unsigned values. The parentheses around `and` are
     * for the reader: Kotlin binds a named infix function tighter than `==`, the opposite of Java.
     */
    private fun ByteArray.startsWith(prefix: IntArray): Boolean =
        size >= prefix.size &&
            prefix.withIndex().all { (i, expected) -> (this[i].toInt() and BYTE_MASK) == expected }

    /**
     * Whether a four-byte container tag and brand sit where the format puts them: RIFF at 0 and
     * `WEBP` at 8, `ftyp` at 4 and `avif` at 8. The same two offsets, so one function reads both.
     */
    private fun ByteArray.isContainer(
        container: String,
        brand: String
    ): Boolean {
        if (size < HEADER_BYTES) return false
        val containerAt = if (container == "RIFF") 0 else TAG_OFFSET
        return String(this, containerAt, TAG_LENGTH, Charsets.US_ASCII) == container &&
            String(this, BRAND_OFFSET, TAG_LENGTH, Charsets.US_ASCII) == brand
    }

    private companion object {
        /** Enough bytes for a container tag at offset 4 and a brand at offset 8. */
        const val HEADER_BYTES = 12
        const val TAG_LENGTH = 4
        const val TAG_OFFSET = 4
        const val BRAND_OFFSET = 8
        const val BYTE_MASK = 0xFF

        /**
         * Types this JVM has a reader for, so a null measurement means the file is broken. WebP and
         * AVIF absent: no reader, so refusing on an unreadable header would refuse every one (#819).
         */
        val MEASURABLE_TYPES = setOf("image/jpeg", "image/png", "image/gif")

        val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF)
        val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val GIF_MAGIC = intArrayOf(0x47, 0x49, 0x46, 0x38)
    }
}

/** What one fetch attempt produced. */
sealed interface ImageFetchResult {
    /** The venue answered 304, so what we recorded before still stands. */
    data object NotModified : ImageFetchResult

    /**
     * The URL produced no image we may store; one case for a refusal and a failure alike, because
     * the caller records both and stops asking for a while.
     */
    data class Rejected(
        val reason: String
    ) : ImageFetchResult

    /**
     * A plain class: an array in a data class gives it an `equals` that compares references.
     */
    @Suppress("LongParameterList") // A value carrier for one fetched image: every parameter is a field of it.
    class Success(
        val bytes: ByteArray,
        val contentHash: String,
        val contentType: String,
        val byteSize: Long,
        /** Null where the JVM has no reader for the format. imgproxy fills it in at PR 4a. */
        val width: Int?,
        val height: Int?,
        val etag: String?,
        val lastModified: String?
    ) : ImageFetchResult
}

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
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Downloads one venue image and describes it, without keeping the bytes.
 *
 * **Uses the scraper's throttled client** ([SCRAPER_WEB_CLIENT]), so an image fetch obeys
 * `robots.txt` and shares the venue's per-host politeness timer with the page fetches. Image
 * fetching is a new class of outbound request and ADR-007 applies to it unchanged — a second client
 * would give the same host two independent timers.
 *
 * **It returns a description, not a file.** PR 3 records what an image is; PR 4 brings the storage
 * client. The hash is computed here so that two events sharing one poster converge on one object
 * later.
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
        validator.reject(url)?.let { return ImageFetchResult.Rejected(it) }

        return try {
            webClient
                .get()
                .uri(url)
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

                        // Declared too large: refuse before reading a byte. The header can be absent
                        // or wrong, so `describe` still measures what actually arrives — this only
                        // saves the download in the honest case.
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
            // Deliberately every transport fault. A connect reset, a DNS failure and a read timeout
            // all mean the same thing to the caller, and enumerating them would only add ways to
            // miss one. Nothing is rethrown, so one dead URL cannot stop the pass.
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            // The reason is logged, and never reaches a metric tag: a tag fed by an exception
            // message is unbounded cardinality.
            logger.debug(e) { "Image fetch failed for $url" }
            // The codec's own limit fires before `describe` ever sees the bytes, so without this an
            // oversized image is recorded as a transport fault. It is a size refusal and has to read
            // as one, or an operator debugging a missing image chases the network instead.
            if (e.isBufferLimit()) {
                ImageFetchResult.Rejected("larger than the ${properties.maxBytes} byte buffer limit")
            } else {
                ImageFetchResult.Rejected("fetch failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Applies the size, type and pixel limits, and measures what survives them.
     *
     * One guard clause per limit, which is why it exceeds the return-count rule. Nesting them would
     * bury which check refused a file, and each refusal reason is stored and read by an operator.
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
        // A type this JVM can read, that it then cannot read, is a corrupt file. A type it has no
        // reader for tells us nothing, so it is not evidence of anything and must not refuse.
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
     * Identifies the file from its own first bytes.
     *
     * **Never from the `Content-Type` header**, which the venue's server controls. An SVG served
     * from our origin executes script in our origin, so what a file *is* has to decide, not what it
     * claims (ADR-019 §4). Anything not on this list is refused, SVG included.
     *
     * **This list is the control, and imgproxy is not.** imgproxy reads SVG as a source quite
     * happily, so nothing downstream would stop one. It reads all five of these as sources too,
     * which is why WebP and AVIF belong here even though this JVM cannot measure them.
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
     * Width and height from the file header, without decoding the pixels.
     *
     * `getWidth` reads only as far as the header, so a decompression bomb is measured rather than
     * allocated for. Decoding proper is imgproxy's job from PR 4a, outside this JVM.
     *
     * **Null for WebP and AVIF, and that is not a failure.** A stock JDK ships readers for JPEG,
     * PNG, GIF, BMP, TIFF and WBMP, and for nothing else. Adding one would mean a library that
     * decodes untrusted bytes inside this process, which is the thing ADR-020 moved out of it.
     * imgproxy reports the real dimensions when it generates the derivatives.
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

    /** Whether this fault, or anything under it, is the codec refusing an oversized body. */
    private fun Throwable.isBufferLimit(): Boolean = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.any { it is DataBufferLimitException }

    /**
     * Whether these bytes open with [prefix], compared as unsigned values.
     *
     * The parentheses around `and` are for the reader rather than the compiler. Kotlin binds a named
     * infix function **tighter** than `==`, which is the opposite of Java's `&`, so the unbracketed
     * form is already correct and reads as though it is not.
     */
    private fun ByteArray.startsWith(prefix: IntArray): Boolean =
        size >= prefix.size &&
            prefix.withIndex().all { (i, expected) -> (this[i].toInt() and BYTE_MASK) == expected }

    /**
     * Whether a four-byte container tag and a four-byte brand sit where the format puts them.
     *
     * RIFF names itself at offset 0 and its form at offset 8, so `RIFF….WEBP` is a WebP. ISO base
     * media files carry `ftyp` at offset 4 and the brand at offset 8, so `….ftypavif` is an AVIF.
     * Both land on the same two offsets, which is why one function reads both.
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
         * Types this JVM has a reader for, so a null measurement means the file is broken.
         *
         * WebP and AVIF are deliberately absent: no reader exists for them, so refusing on an
         * unreadable header would refuse every one of them (#819 review).
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
     * The URL produced no image we may store, and the reason goes into the negative cache.
     *
     * One case for a refusal and a failure alike, because the caller does the same thing with both:
     * record it and stop asking for a while.
     */
    data class Rejected(
        val reason: String
    ) : ImageFetchResult

    /**
     * A plain class rather than a `data` one: it carries the bytes, and an array in a data class
     * gives it an `equals` that compares references. Nothing here is compared by value.
     */
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

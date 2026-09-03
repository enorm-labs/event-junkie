package de.norm.events.image

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** Where the serving route is mounted. The prefix is here rather than in a base path (#857). */
const val IMAGES_PATH = "/api/images"

/**
 * Serves our own copy of a venue image, so a visitor's browser never contacts the venue (ADR-019).
 *
 * **The URL is content addressed and therefore immutable**, which is what makes a one-year
 * `Cache-Control` correct rather than optimistic: the hash is of the bytes, so one URL can only ever
 * return one file. A venue replacing its poster produces a different hash and a different URL, and
 * nothing has to be invalidated anywhere.
 *
 * **It is on our own origin rather than on an image host of its own.** ADR-012 removed the CDN and
 * §5 of both privacy notices says that no third party sits in front of the site; a second hostname
 * would need its own DNS record, its own certificate and its own ingress rule to say what this says
 * by construction. It also keeps `Content-Security-Policy: img-src 'self'` reachable, which ADR-019
 * names as one of the things caching makes possible.
 */
@RestController
@RequestMapping(IMAGES_PATH)
@Tag(name = "Images", description = "Cached venue imagery, served from our own origin")
class CachedImageController(
    private val repository: CachedImageRepository,
    private val reader: ImageObjectReader,
    private val metrics: ImageServingMetrics
) {
    /**
     * Returns one derivative.
     *
     * The file name is parsed here rather than split into two path variables, so a name that is not
     * `<width>.<format>` is refused before anything reaches the database — and so the URL keeps an
     * extension, which is what makes it look like a file to everything that handles one.
     */
    @GetMapping("/{contentHash}/{file}")
    @Operation(summary = "Get a cached venue image at one width and format")
    suspend fun serve(
        @Parameter(description = "SHA-256 of the original bytes, lower-case hex.", required = true)
        @PathVariable contentHash: String,
        @Parameter(description = "Width and format, as `<width>.<format>`.", example = "768.jpg", required = true)
        @PathVariable file: String
    ): ResponseEntity<ByteArray> {
        val request = parse(contentHash, file)
        val storageKey = request?.let { repository.findStorageKey(it.contentHash, it.width, it.format) }
        if (request == null || storageKey == null) {
            metrics.record(ImageServingMetrics.Outcome.UNKNOWN)
            return notFound()
        }
        return respond(reader.read(storageKey), request.format)
    }

    /**
     * **The metric is recorded here rather than derived from the status code**, because two of these
     * three are 404s that mean opposite things: a path nobody ever published, and a row promising an
     * object the bucket does not have. `http_server_requests` cannot tell them apart.
     */
    private fun respond(
        image: ImageObject,
        format: String
    ): ResponseEntity<ByteArray> =
        when (image) {
            is ImageObject.Found -> {
                metrics.record(ImageServingMetrics.Outcome.FOUND)
                ok(image.bytes, format)
            }

            ImageObject.Missing -> {
                metrics.record(ImageServingMetrics.Outcome.MISSING)
                notFound()
            }

            // Not a 404. The object exists as far as anything here knows, and telling a browser to
            // remember an absence caused by our own bucket being unreachable would outlast the fault.
            ImageObject.Unavailable -> {
                metrics.record(ImageServingMetrics.Outcome.UNAVAILABLE)
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            }
        }

    private fun ok(
        bytes: ByteArray,
        format: String
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MEDIA_TYPES.getValue(format))
            .cacheControl(CacheControl.maxAge(CACHE_LIFETIME).cachePublic().immutable())
            // The bytes are a venue's file. `nosniff` is set on every response by the ingress, and it
            // is set again here so the guarantee holds in a deployment with no Traefik in front.
            .header(CONTENT_TYPE_OPTIONS, "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(bytes)

    private fun notFound(): ResponseEntity<ByteArray> = ResponseEntity.notFound().build()

    /**
     * Reads the two path variables, or refuses them.
     *
     * The shapes are checked before the query, not to protect it — the key comes from the row and
     * never from the request — but so that a crawler walking made-up paths costs a regular expression
     * each time rather than a database round trip.
     */
    private fun parse(
        contentHash: String,
        file: String
    ): ImageRequest? {
        if (!CONTENT_HASH.matches(contentHash)) return null
        return FILE_NAME
            .matchEntire(file)
            ?.destructured
            ?.let { (width, format) -> ImageRequest(contentHash, width.toInt(), format) }
    }

    private data class ImageRequest(
        val contentHash: String,
        val width: Int,
        val format: String
    )

    private companion object {
        /** A year. The URL names the bytes, so it can never be stale. */
        val CACHE_LIFETIME: Duration = Duration.ofDays(365)

        /** Spring's [HttpHeaders] has no constant for this one. */
        const val CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

        val CONTENT_HASH = Regex("^[0-9a-f]{64}$")

        /**
         * The formats come from [ImageFormats], which is also what the `<picture>` sources are built
         * from — so a format cannot be offered to a browser and refused here. Four digits caps the
         * width at 9999, well above the widest derivative generated.
         */
        val FILE_NAME = Regex("^([0-9]{1,4})\\.(${ImageFormats.ORDERED.joinToString("|")})$")

        /** Parsed once, off the map [ImageFormats] serves from, so the two key sets cannot drift. */
        val MEDIA_TYPES = ImageFormats.MEDIA_TYPES.mapValues { (_, type) -> MediaType.parseMediaType(type) }
    }
}

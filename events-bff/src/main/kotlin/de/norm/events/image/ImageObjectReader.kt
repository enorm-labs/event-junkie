package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * Reads one derivative out of the bucket.
 *
 * **Whole bytes rather than a streamed body, and the size bound is what makes that safe.** A
 * derivative is imgproxy output at one of four widths, so it is tens of kilobytes; the widest is not
 * megabytes. Buffering keeps `Content-Length` exact and the failure handling honest — a transport
 * fault surfaces here, before a single byte of a 200 response has been written, instead of
 * truncating a response that already claimed success.
 *
 * **Only derivatives are ever read.** The key comes from a `cached_image_variant` row, and nothing
 * in this module can name the `originals/` prefix — which is what keeps an original's EXIF, and the
 * bytes a venue actually served, off the wire (ADR-020 §"What this obliges").
 *
 * **The bucket is behind [ImageObjectCache]**, so a key served recently costs no round trip at all.
 * Object Storage is Ceph on hard disks, so a miss is a seek (#847).
 */
@Component
class ImageObjectReader(
    private val client: S3AsyncClient?,
    private val properties: ImageServingProperties,
    private val cache: ImageObjectCache
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Returns [storageKey], distinguishing the three outcomes a caller has to answer differently.
     *
     * Reads through [ImageObjectCache], so a key this process served recently costs no round trip.
     */
    suspend fun read(storageKey: String): ImageObject = cache.get(storageKey, ::fetch)

    private suspend fun fetch(storageKey: String): ImageObject {
        val s3 = client ?: return ImageObject.Unavailable

        return try {
            val response =
                s3
                    .getObject(
                        GetObjectRequest
                            .builder()
                            .bucket(properties.storage.bucket)
                            .key(storageKey)
                            .build(),
                        AsyncResponseTransformer.toBytes()
                    ).await()
            ImageObject.Found(response.asByteArray())
        } catch (e: NoSuchKeyException) {
            // A row promised an object that is not there. Worth a warning rather than a debug line:
            // the sweep deletes objects and the row is what it asks, so this is the shape of a sweep
            // that deleted something it should have kept.
            logger.warn(e) { "Variant row points at missing object $storageKey" }
            ImageObject.Missing
        } catch (
            // The SDK wraps transport, signature and permission faults in unrelated types, and the
            // caller does the same thing with all of them: report the store as unavailable rather
            // than tell a browser to cache an absence.
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            logger.warn(e) { "Could not read $storageKey" }
            ImageObject.Unavailable
        }
    }
}

/**
 * What came back, in the three states that lead to three different responses.
 *
 * [Missing] and [Unavailable] both mean no bytes, and conflating them would be a caching bug: an
 * object that is genuinely gone is a 404 a browser may remember, and a store we cannot reach is a
 * 503 it must not.
 */
sealed interface ImageObject {
    /** Not a data class: generated equality on an array compares identity, and nothing compares these. */
    class Found(
        val bytes: ByteArray
    ) : ImageObject

    data object Missing : ImageObject

    data object Unavailable : ImageObject
}

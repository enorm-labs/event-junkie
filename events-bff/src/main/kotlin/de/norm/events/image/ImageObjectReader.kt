package de.norm.events.image

import de.norm.events.LogContextConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

/**
 * Reads one derivative out of the bucket. Whole bytes rather than a streamed body, safe because a
 * derivative is imgproxy output at one of five widths, tens of kilobytes: `Content-Length` stays
 * exact and a transport fault surfaces before a byte of a 200 has been written. Only
 * derivatives are read: the key comes from a `cached_image_variant` row and nothing here can
 * name `originals/`, which keeps an original's EXIF off the wire (ADR-020 §"What this
 * obliges"). Behind [ImageObjectCache], since Object Storage is Ceph on hard disks (#847).
 */
@Component
class ImageObjectReader(
    private val client: S3AsyncClient?,
    private val properties: ImageServingProperties,
    private val cache: ImageObjectCache
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Returns [storageKey] in the three outcomes a caller answers differently, through
     * [ImageObjectCache].
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
            // A row promised an object that is not there: a warning, the shape of a sweep that deleted
            // something it should have kept.
            logger.at(Level.WARN) {
                message = "Variant row points at missing object"
                cause = e
                payload = mapOf(LogContextConfiguration.STORAGE_KEY to storageKey)
            }
            ImageObject.Missing
        } catch (
            // The SDK wraps transport, signature and permission faults in unrelated types; all of them
            // mean the store is unavailable, never an absence a browser may cache.
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            logger.at(Level.WARN) {
                message = "Could not read the object"
                cause = e
                payload = mapOf(LogContextConfiguration.STORAGE_KEY to storageKey)
            }
            ImageObject.Unavailable
        }
    }
}

/**
 * What came back. [Missing] and [Unavailable] both mean no bytes, and conflating them would be a
 * caching bug: a 404 a browser may remember against a 503 it must not.
 */
sealed interface ImageObject {
    /** Not a data class: generated equality on an array compares identity, and nothing compares these. */
    class Found(
        val bytes: ByteArray
    ) : ImageObject

    data object Missing : ImageObject

    data object Unavailable : ImageObject
}

package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Instant

/**
 * Writes a cached image to object storage, and says where it went.
 *
 * **Content addressed.** The key carries the SHA-256 of the bytes, so the same poster on two events
 * is one object, and an object's contents can never change under its key. That is what makes the
 * long `Cache-Control` this design serves with safe (ADR-019 §3.1), and it is why the sweep in a
 * later step can reason about orphans at all.
 *
 * **Originals are kept as well as derivatives** (ADR-019 §2.6). imgproxy derives from a source and
 * needs one to exist, and keeping it means a new width or format later never means asking a venue
 * for the file again — which ADR-007's politeness position values above the storage.
 *
 * **An original carries its EXIF and must never be served.** Only derivatives reach a browser, and
 * the BFF has no route to this prefix.
 */
@Component
class ImageStorage(
    private val client: S3AsyncClient?,
    private val properties: ImageStorageProperties
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Whether a client exists to store through.
     *
     * The caller needs this to tell **"nothing is configured"** from **"the store failed"**. They
     * look identical from a null key and mean opposite things: the first is a local run without
     * credentials and is fine, the second is our own infrastructure and must be retried.
     */
    fun isEnabled(): Boolean = client != null

    /**
     * Stores [bytes] as the original for [contentHash] and returns its key.
     *
     * Returns null when no client is configured, which is the state a local run without credentials
     * is in. The caller records the image either way — a row with no object is a row PR 4a will
     * pick up, and it is not a failure of the fetch.
     */
    suspend fun storeOriginal(
        contentHash: String,
        contentType: String,
        bytes: ByteArray
    ): String? = put(originalKey(contentHash), contentType, bytes)

    private suspend fun put(
        key: String,
        contentType: String,
        bytes: ByteArray
    ): String? {
        val s3 = client ?: return null

        return try {
            s3
                .putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(properties.bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                    AsyncRequestBody.fromBytes(bytes)
                ).await()
            logger.debug { "Stored $key (${bytes.size} bytes)" }
            key
        } catch (
            // The SDK wraps transport, signature and permission faults in different types, and the
            // caller does the same thing with all of them: record nothing and try again next pass.
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            logger.warn(e) { "Could not store $key" }
            null
        }
    }

    /**
     * The key an original lives at.
     *
     * The environment prefix comes first, so a listing scoped to one environment is a prefix query
     * rather than a filter — which is what lets the sweep stay inside its own environment.
     */
    fun originalKey(contentHash: String): String = "${keyPrefix()}$ORIGINALS/$contentHash"

    /**
     * The key a derivative lives at.
     *
     * The hash comes before the width, so every derivative of one original shares a prefix. That is
     * what lets the orphan sweep delete an image's whole family with one listing rather than
     * reconstructing the width and format set it was generated with.
     */
    fun derivativeKey(
        contentHash: String,
        width: Int,
        format: String
    ): String = "${keyPrefix()}$DERIVED/$contentHash/$width.$format"

    /** Stores a derivative and returns its key, or null when there is no client or the put failed. */
    suspend fun storeDerivative(
        contentHash: String,
        width: Int,
        format: String,
        bytes: ByteArray
    ): String? = put(derivativeKey(contentHash, width, format), "image/$format", bytes)

    /**
     * Everything this environment has stored.
     *
     * The prefix is applied here rather than passed in, so a caller cannot list the other
     * environment's objects and then decide they are unreferenced (ADR-019 §2.8).
     */
    suspend fun listAll(): List<StoredObject> {
        val s3 = client ?: return emptyList()
        val found = mutableListOf<StoredObject>()
        var token: String? = null

        do {
            val page =
                s3
                    .listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(properties.bucket)
                            .prefix(keyPrefix())
                            .continuationToken(token)
                            .build()
                    ).await()
            page.contents().forEach { found += StoredObject(it.key(), it.lastModified()) }
            token = page.nextContinuationToken()
        } while (token != null)

        return found
    }

    /**
     * Deletes [keys] and returns how many went.
     *
     * One request per object rather than a batch delete. The batch API reports per-key failures in
     * the response body instead of the status, so a partial failure reads as success; passes here
     * are hundreds of objects, which is small enough that the simpler call costs nothing.
     */
    suspend fun delete(keys: Collection<String>): Int {
        val s3 = client ?: return 0
        var deleted = 0

        keys.forEach { key ->
            runCatching {
                s3
                    .deleteObject(
                        DeleteObjectRequest
                            .builder()
                            .bucket(properties.bucket)
                            .key(key)
                            .build()
                    ).await()
            }.onSuccess { deleted++ }
                .onFailure { logger.warn(it) { "Could not delete $key" } }
        }

        return deleted
    }

    /** The prefix every key of this environment starts with, trailing slash included. */
    fun keyPrefix(): String = "${properties.prefix}/"

    /**
     * The content hash a key belongs to, or null when the key is not one this class writes.
     *
     * The sweep never deletes a key this cannot name. A key of an unknown shape belongs to
     * something else, and guessing at it is how a sweep reaches an object it does not own.
     */
    fun contentHashOf(key: String): String? {
        val relative = key.removePrefix(keyPrefix()).takeIf { it != key } ?: return null

        return when {
            relative.startsWith("$ORIGINALS/") -> relative.removePrefix("$ORIGINALS/").takeIf { "/" !in it }
            relative.startsWith("$DERIVED/") -> relative.removePrefix("$DERIVED/").substringBefore("/")
            else -> null
        }?.ifBlank { null }
    }

    companion object {
        private const val ORIGINALS = "originals"
        private const val DERIVED = "derived"
    }
}

/** One object in the bucket, as a listing sees it. */
data class StoredObject(
    val key: String,
    val lastModified: Instant
)

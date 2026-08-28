package de.norm.events.image

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * One venue image URL, and what we know about the copy of it.
 *
 * Keyed on [sourceUrl] rather than on an event, because two events sharing one poster are one image.
 * The venue is asked for it once.
 *
 * **A row exists before a fetch succeeds and after one fails.** [failedAt] and [failureReason] are
 * the negative cache (ADR-019 §3.6): a dead URL must not be requested every night forever.
 */
@Table("cached_image")
data class CachedImageEntity(
    @Id val id: Long? = null,
    val sourceUrl: String,
    /** SHA-256 of the bytes, and the storage key from PR 4 onward. Null until a fetch succeeds. */
    val contentHash: String? = null,
    /** Sniffed from the bytes. Never the `Content-Type` header, which the far end controls. */
    val contentType: String? = null,
    val byteSize: Long? = null,
    val intrinsicWidth: Int? = null,
    val intrinsicHeight: Int? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val fetchedAt: Instant? = null,
    val lastSeenAt: Instant? = null,
    val failedAt: Instant? = null,
    val failureReason: String? = null,
    /** Set by the takedown route, so a removed image is not re-fetched by the next pass. */
    val deletedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

/**
 * One file we serve: a width, in a format.
 *
 * Empty until PR 4a, which is where imgproxy generates the derivatives. The table exists now because
 * the shape decides the schema, and adding it later would be a second migration for one decision.
 */
@Table("cached_image_variant")
data class CachedImageVariantEntity(
    @Id val id: Long? = null,
    val cachedImageId: Long,
    val width: Int,
    val format: String,
    val storageKey: String,
    val byteSize: Long,
    val createdAt: Instant? = null
)

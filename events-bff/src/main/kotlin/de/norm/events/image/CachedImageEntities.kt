package de.norm.events.image

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * Read-only R2DBC entity over the four `cached_image_variant` columns this module needs, a lean
 * projection like [de.norm.events.sourcelicence.SourceLicenceEntity]; the importer owns the table.
 */
@Table("cached_image_variant")
data class CachedImageVariantEntity(
    @Id val id: Long? = null,
    val cachedImageId: Long,
    val width: Int,
    val format: String,
    val storageKey: String
)

/**
 * One derivative we could serve for one venue image URL: the venue's URL is what an event row
 * carries, the hash is what our own URL is addressed by. Several rows share a [sourceUrl].
 */
data class ServableVariant(
    val sourceUrl: String,
    val contentHash: String,
    /**
     * The original's own pixel dimensions, the aspect ratio every derivative keeps. Null is a real
     * answer: a stock JVM reads neither WebP nor AVIF, 16% of staging's corpus at import, and
     * imgproxy's numbers arrive with the derivatives but are never guaranteed.
     */
    val intrinsicWidth: Int?,
    val intrinsicHeight: Int?,
    val width: Int,
    val format: String
)

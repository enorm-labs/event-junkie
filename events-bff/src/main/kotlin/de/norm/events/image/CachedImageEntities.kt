package de.norm.events.image

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * Read-only R2DBC entity over the four `cached_image_variant` columns this module needs.
 *
 * A lean projection like [de.norm.events.sourcelicence.SourceLicenceEntity] — the table is owned and
 * written by the importer, and nothing here writes to it. `byte_size` and `created_at` are omitted
 * because serving a file needs neither.
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
 * One derivative we could serve for one venue image URL.
 *
 * The projection the page query returns: the venue's URL is what an event row still carries, and the
 * hash is what our own URL is addressed by. Several rows share a [sourceUrl], one per width.
 */
data class ServableVariant(
    val sourceUrl: String,
    val contentHash: String,
    /**
     * The original's own pixel dimensions, which are the aspect ratio every derivative keeps.
     *
     * **Null is a real answer**, not a defect. A stock JVM reads neither WebP nor AVIF, so
     * `ImageFetcher` measures nothing for those — 16% of staging's corpus at import. imgproxy
     * decodes them and the numbers arrive with the derivatives, so the gap closes but is never
     * guaranteed closed.
     */
    val intrinsicWidth: Int?,
    val intrinsicHeight: Int?,
    val width: Int,
    val format: String
)

package de.norm.events.image

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * The two questions serving asks of the database.
 *
 * Raw SQL over tables another module owns, schema-prefixed with the interpolated constant rather
 * than a literal (ADR-004, #540). Both queries filter `deleted_at IS NULL`, and that is the takedown
 * route working: setting one timestamp stops the image being served everywhere at once, without
 * waiting for the object itself to be swept.
 */
@Repository
interface CachedImageRepository : CoroutineCrudRepository<CachedImageVariantEntity, Long> {
    /**
     * Every derivative available for the given venue image URLs, in one query for a whole page.
     *
     * A join rather than two round trips, because the caller needs both halves of the answer: the
     * hash addresses our URL, and the width set decides which of them it may point at. A page of
     * twenty events returns at most twenty times the generated width count.
     */
    @Query(
        """
        SELECT c.source_url, c.content_hash, c.intrinsic_width, c.intrinsic_height, v.width, v.format
        FROM $EVENTS_SCHEMA.cached_image c
        JOIN $EVENTS_SCHEMA.cached_image_variant v ON v.cached_image_id = c.id
        WHERE c.deleted_at IS NULL
          AND c.content_hash IS NOT NULL
          AND c.source_url IN (:sourceUrls)
        """
    )
    fun findServableBySourceUrlIn(sourceUrls: Collection<String>): Flow<ServableVariant>

    /**
     * The object key behind one served URL, or null if there is nothing to serve.
     *
     * **This is the allow-list, and it is why the route needs no path sanitising.** The key comes
     * out of a row the importer wrote, so a request can only ever name an object we generated — it
     * cannot reach the `originals/` prefix, another environment's prefix, or anything outside the
     * bucket, whatever the path variables contain.
     */
    @Query(
        """
        SELECT v.storage_key
        FROM $EVENTS_SCHEMA.cached_image_variant v
        JOIN $EVENTS_SCHEMA.cached_image c ON c.id = v.cached_image_id
        WHERE c.deleted_at IS NULL
          AND c.content_hash = :contentHash
          AND v.width = :width
          AND v.format = :format
        """
    )
    suspend fun findStorageKey(
        contentHash: String,
        width: Int,
        format: String
    ): String?
}

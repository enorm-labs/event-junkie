package de.norm.events.image

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * The two questions serving asks of the database: raw SQL over tables another module owns,
 * schema-prefixed with the constant (ADR-004, #540). Both filter `deleted_at IS NULL`, which is
 * the takedown route: one timestamp stops the image being served everywhere at once.
 */
@Repository
interface CachedImageRepository : CoroutineCrudRepository<CachedImageVariantEntity, Long> {
    /**
     * Every derivative for the given venue image URLs, one query for a whole page: a join, because
     * the hash addresses our URL and the width set decides which of them it may point at.
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
     * The object key behind one served URL, or null. This is the allow-list, and why the route needs
     * no path sanitising: the key comes out of a row the importer wrote, so a request cannot reach
     * `originals/` or another environment's prefix. `DISTINCT` because `content_hash` is not
     * unique: byte-identical files under two URLs get two rows on one hash, 24 of production's 1118,
     * naming one object since the key carries no row id. Two rows that disagreed on the key would
     * still fail here, the case worth failing on.
     */
    @Query(
        """
        SELECT DISTINCT v.storage_key
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

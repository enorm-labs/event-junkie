package de.norm.events.image

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface CachedImageRepository : CoroutineCrudRepository<CachedImageEntity, Long> {
    suspend fun findBySourceUrl(sourceUrl: String): CachedImageEntity?

    /**
     * Venue image URLs that no [CachedImageEntity] covers yet.
     *
     * Raw SQL over `event` rather than a call into the event module, because all this needs is one
     * column — see [ImageModule]. Schema-prefixed with the interpolated constant rather than a
     * literal (ADR-004, #540).
     *
     * **A source that prohibits its images has no URL here to find.** #807 made the importer store
     * `null` for a prohibited `image_url`, so the exclusion is structural rather than a predicate
     * somebody has to remember to write.
     *
     * `DISTINCT` because two events sharing a poster must not be two fetches.
     */
    @Query(
        """
        SELECT DISTINCT e.image_url
        FROM $EVENTS_SCHEMA.event e
        WHERE e.image_url IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.cached_image c WHERE c.source_url = e.image_url)
        LIMIT :limit
        """
    )
    fun findUncachedImageUrls(limit: Int): Flow<String>

    /**
     * Rows worth asking about again: a success old enough to re-check, or a failure past its cooldown.
     *
     * A deleted row is never a candidate. That is what stops a takedown being undone by the next pass.
     */
    @Query(
        """
        SELECT * FROM $EVENTS_SCHEMA.cached_image
        WHERE deleted_at IS NULL
          AND ((fetched_at IS NOT NULL AND fetched_at < :refreshBefore)
            OR (failed_at IS NOT NULL AND failed_at < :retryBefore))
        ORDER BY COALESCE(fetched_at, failed_at)
        LIMIT :limit
        """
    )
    fun findDueForRefresh(
        refreshBefore: Instant,
        retryBefore: Instant,
        limit: Int
    ): Flow<CachedImageEntity>
}

@Repository
interface CachedImageVariantRepository : CoroutineCrudRepository<CachedImageVariantEntity, Long> {
    fun findByCachedImageId(cachedImageId: Long): Flow<CachedImageVariantEntity>
}

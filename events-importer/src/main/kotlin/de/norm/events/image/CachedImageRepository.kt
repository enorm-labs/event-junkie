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
     * Stored images that have no derivative yet.
     *
     * A left join rather than `NOT EXISTS` on a count, because an image whose generation was
     * interrupted has *some* variants and still needs the rest. Asking for images with none at all
     * would skip it forever.
     */
    @Query(
        """
        SELECT c.* FROM $EVENTS_SCHEMA.cached_image c
        WHERE c.content_hash IS NOT NULL
          AND c.deleted_at IS NULL
          AND (SELECT count(*) FROM $EVENTS_SCHEMA.cached_image_variant v WHERE v.cached_image_id = c.id) < :expectedVariants
        ORDER BY c.fetched_at
        LIMIT :limit
        """
    )
    fun findNeedingDerivatives(
        expectedVariants: Int,
        limit: Int
    ): Flow<CachedImageEntity>

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

    /**
     * The images one venue's events point at, for the opt-out route in `SCRAPING_POSITION.md` §5.
     *
     * Joined through `event` rather than through the source, because a venue can have several
     * sources and an operator objects to the venue. Already-deleted rows are skipped, so running the
     * takedown twice is not two deletions.
     */
    @Query(
        """
        SELECT c.* FROM $EVENTS_SCHEMA.cached_image c
        WHERE c.deleted_at IS NULL
          AND EXISTS (
              SELECT 1 FROM $EVENTS_SCHEMA.event e
              JOIN $EVENTS_SCHEMA.venue v ON v.id = e.venue_id
              WHERE e.image_url = c.source_url AND v.slug = :venueSlug
          )
        """
    )
    fun findByVenueSlug(venueSlug: String): Flow<CachedImageEntity>

    /**
     * Rows no event points at any more, which is what makes their objects orphans.
     *
     * Stale-event cleanup and the `PROHIBITED` licence route both leave these behind, and neither
     * knows this table exists. A tombstoned row is excluded: a takedown is a decision, and dropping
     * the row would let the next import fetch the image again.
     */
    @Query(
        """
        SELECT c.* FROM $EVENTS_SCHEMA.cached_image c
        WHERE c.deleted_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event e WHERE e.image_url = c.source_url)
        ORDER BY c.id
        LIMIT :limit
        """
    )
    fun findUnreferenced(limit: Int): Flow<CachedImageEntity>

    /**
     * Every content hash still claimed by a row, which is the sweep's whole answer for the bucket.
     *
     * A tombstone claims nothing — its objects are meant to be gone — so `deleted_at` is excluded
     * here even though [findUnreferenced] preserves it.
     */
    @Query(
        """
        SELECT DISTINCT content_hash FROM $EVENTS_SCHEMA.cached_image
        WHERE content_hash IS NOT NULL AND deleted_at IS NULL
        """
    )
    fun findLiveContentHashes(): Flow<String>

    /**
     * Whether another live row still holds [contentHash].
     *
     * Keys are the hash of the bytes, so two venues publishing the same file share one object.
     * Deleting it for one of them would blank the other's card, and the row would go on claiming it.
     */
    @Query(
        """
        SELECT count(*) FROM $EVENTS_SCHEMA.cached_image
        WHERE content_hash = :contentHash AND deleted_at IS NULL AND id <> :excludingId
        """
    )
    suspend fun countOtherLiveWithHash(
        contentHash: String,
        excludingId: Long
    ): Long
}

@Repository
interface CachedImageVariantRepository : CoroutineCrudRepository<CachedImageVariantEntity, Long> {
    fun findByCachedImageId(cachedImageId: Long): Flow<CachedImageVariantEntity>
}

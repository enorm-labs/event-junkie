package de.norm.events.image

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Every column an image URL can live in.
 *
 * **The fetcher reads this and the sweep asks its reverse, so they must not drift.** A column the
 * fetcher covers and the sweep does not is an image deleted moments after it is stored, on a loop.
 * One definition is what makes that impossible rather than merely unlikely.
 *
 * `UNION` rather than `UNION ALL`: two events sharing a poster must be one fetch, and the same URL
 * on a venue and on its event is one object.
 */
private const val IMAGE_URL_SOURCES = """
    SELECT image_url FROM $EVENTS_SCHEMA.event WHERE image_url IS NOT NULL
    UNION SELECT image_url FROM $EVENTS_SCHEMA.venue WHERE image_url IS NOT NULL
    UNION SELECT image_url FROM $EVENTS_SCHEMA.artist WHERE image_url IS NOT NULL
    UNION SELECT image_url FROM $EVENTS_SCHEMA.promoter WHERE image_url IS NOT NULL
"""

@Repository
interface CachedImageRepository : CoroutineCrudRepository<CachedImageEntity, Long> {
    suspend fun findBySourceUrl(sourceUrl: String): CachedImageEntity?

    /**
     * Image URLs that no [CachedImageEntity] covers yet, from all four tables.
     *
     * Raw SQL rather than calls into four modules, because all this needs is one column — see
     * [ImageModule]. Schema-prefixed with the interpolated constant rather than a literal
     * (ADR-004, #540).
     *
     * **A source that prohibits its images has no URL here to find.** #807 made the importer store
     * `null` for a prohibited `image_url`, so the exclusion is structural rather than a predicate
     * somebody has to remember to write. That reasoning covers `event` alone: the other three
     * columns are written by the admin API and no `event_source` licence applies to them (#833).
     */
    @Query(
        """
        SELECT u.image_url FROM ($IMAGE_URL_SOURCES) u
        WHERE NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.cached_image c WHERE c.source_url = u.image_url)
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
     * Everything one venue's takedown covers, for the opt-out route in `SCRAPING_POSITION.md` §5.
     *
     * Two sources: the images its events point at, and the venue's own `image_url`. Joined through
     * `event` rather than through the source, because a venue can have several sources and an
     * operator objects to the venue.
     *
     * **Artist and promoter images are deliberately absent.** An artist plays many venues, so
     * deleting their photograph on one venue's request would remove it from every other venue's
     * listing. Their route is the Art. 21 objection in §7.3 of `docs/LEGAL.md`, not this one.
     *
     * Already-deleted rows are skipped, so running the takedown twice is not two deletions.
     */
    @Query(
        """
        SELECT c.* FROM $EVENTS_SCHEMA.cached_image c
        WHERE c.deleted_at IS NULL
          AND (
              EXISTS (
                  SELECT 1 FROM $EVENTS_SCHEMA.event e
                  JOIN $EVENTS_SCHEMA.venue v ON v.id = e.venue_id
                  WHERE e.image_url = c.source_url AND v.slug = :venueSlug
              )
              OR EXISTS (
                  SELECT 1 FROM $EVENTS_SCHEMA.venue v
                  WHERE v.image_url = c.source_url AND v.slug = :venueSlug
              )
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
          AND NOT EXISTS (SELECT 1 FROM ($IMAGE_URL_SOURCES) u WHERE u.image_url = c.source_url)
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

    /**
     * Every referenced image URL, split into the four states one can be in.
     *
     * One query rather than four: the universe is the same four-table `UNION` the fetcher walks, and
     * the gauges behind this refresh every minute. **The counts are disjoint and sum to every image
     * URL the site references**, which is what makes them safe to stack on a graph.
     *
     * A tombstoned row is `withheld` rather than `pending`, and the difference is not cosmetic — a
     * taken-down URL is never fetched again ([findUncachedImageUrls] excludes any URL with a row),
     * so counting it as outstanding work would show a backlog that can never drain.
     *
     * `FILTER` rather than four queries or four `CASE` sums, and `LEFT JOIN` rather than `EXISTS`
     * because `cached_image.source_url` is unique: at most one row joins, so nothing is counted
     * twice.
     *
     * **The column aliases are the contract** — R2DBC maps a projection by label, nothing checks it
     * at compile time, and `ImageMetricsIntegrationTest` is what runs this against a real database.
     */
    @Query(
        """
        SELECT
            count(*) FILTER (WHERE c.deleted_at IS NULL AND c.content_hash IS NOT NULL) AS cached,
            count(*) FILTER (WHERE c.deleted_at IS NULL AND c.content_hash IS NULL AND c.failed_at IS NOT NULL) AS failed,
            count(*) FILTER (WHERE c.id IS NULL OR (c.deleted_at IS NULL AND c.content_hash IS NULL AND c.failed_at IS NULL)) AS pending,
            count(*) FILTER (WHERE c.deleted_at IS NOT NULL) AS withheld
        FROM ($IMAGE_URL_SOURCES) u
        LEFT JOIN $EVENTS_SCHEMA.cached_image c ON c.source_url = u.image_url
        """
    )
    suspend fun countUrlStates(): ImageUrlCountsRow

    /**
     * Stored images still short of their derivatives — [findNeedingDerivatives] asked as a number.
     *
     * The predicate is that query's, deliberately duplicated rather than derived: the two answer the
     * same question, and a count that drifts from the batch it describes reports a backlog draining
     * while the pass works on something else.
     */
    @Query(
        """
        SELECT count(*) FROM $EVENTS_SCHEMA.cached_image c
        WHERE c.content_hash IS NOT NULL
          AND c.deleted_at IS NULL
          AND (SELECT count(*) FROM $EVENTS_SCHEMA.cached_image_variant v WHERE v.cached_image_id = c.id) < :expectedVariants
        """
    )
    suspend fun countNeedingDerivatives(expectedVariants: Int): Long
}

/**
 * The four states a referenced image URL can be in, as one row.
 *
 * Field names are the query's column aliases. Renaming one here without renaming it there compiles
 * and returns zeros.
 */
data class ImageUrlCountsRow(
    val cached: Long,
    val failed: Long,
    val pending: Long,
    val withheld: Long
)

@Repository
interface CachedImageVariantRepository : CoroutineCrudRepository<CachedImageVariantEntity, Long> {
    fun findByCachedImageId(cachedImageId: Long): Flow<CachedImageVariantEntity>
}

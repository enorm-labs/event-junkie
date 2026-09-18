package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ArtistRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /** Finds all artists with pagination and sorting applied via [pageable]. */
    fun findAllBy(pageable: Pageable): Flow<ArtistEntity>

    /** Finds a single artist by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): ArtistEntity?

    /** Batch-fetches artists by their slugs. Used by the import pipeline to avoid N+1 queries. */
    fun findBySlugIn(slugs: Collection<String>): Flow<ArtistEntity>

    /**
     * Inserts an artist only if its [slug] is not already taken, returning the number of rows
     * inserted (`1` if created, `0` if it already existed).
     *
     * `ON CONFLICT DO NOTHING` makes this a no-op instead of raising on a duplicate slug, so a
     * concurrent import that inserts the same artist first does **not** abort the caller's
     * transaction — the Postgres pitfall that a caught unique-violation still poisons the
     * surrounding transaction. `created_at`/`updated_at` fall back to their `DEFAULT now()`.
     */
    @Modifying
    @Query("INSERT INTO $EVENTS_SCHEMA.artist (name, slug) VALUES (:name, :slug) ON CONFLICT (slug) DO NOTHING")
    suspend fun insertIfAbsent(
        name: String,
        slug: String
    ): Int

    /**
     * The rows the MusicBrainz sweep still owes a verdict, among [ids]: never checked, or renamed
     * since they were (`updated_at` moves on every save, `musicbrainz_checked_at` only on a verdict).
     */
    @Query(
        """
        SELECT * FROM $EVENTS_SCHEMA.artist
        WHERE id IN (:ids)
          AND (musicbrainz_match = 'UNCHECKED' OR updated_at > musicbrainz_checked_at)
        ORDER BY id
        """
    )
    fun findNeedingMusicBrainzLookup(ids: Collection<Long>): Flow<ArtistEntity>

    /** The oldest rows the sweep has never looked at — the backfill's slice, served by the partial index of V037. */
    @Query("SELECT * FROM $EVENTS_SCHEMA.artist WHERE musicbrainz_match = 'UNCHECKED' ORDER BY id LIMIT :limit")
    fun findUncheckedByMusicBrainz(limit: Int): Flow<ArtistEntity>

    /** How many rows still carry [MusicBrainzMatch.UNCHECKED]; the gauge that shows the backfill draining. */
    @Query("SELECT count(*) FROM $EVENTS_SCHEMA.artist WHERE musicbrainz_match = 'UNCHECKED'")
    suspend fun countUncheckedByMusicBrainz(): Long

    /**
     * Stores one verdict and touches nothing else.
     *
     * Not a `save`, on purpose: `save` writes every column, and the name is never rewritten from a
     * verdict (ADR-031). The id is nulled unless the match is EXACT, which is also what the CHECK
     * constraint of V037 demands.
     *
     * **`now()` in SQL, not a timestamp from the JVM.** `trg_artist_updated_at` (V001) sets
     * `updated_at = now()` on every UPDATE, this one included, and [findNeedingMusicBrainzLookup]
     * reads `updated_at > musicbrainz_checked_at` as "renamed since". Both `now()` calls in one
     * statement are the same instant, so the two columns come out equal and the row is not queued
     * again; a clock read a millisecond earlier on the JVM would queue every row forever.
     */
    @Modifying
    @Query(
        """
        UPDATE $EVENTS_SCHEMA.artist
        SET musicbrainz_match = :match, musicbrainz_id = :mbid, musicbrainz_checked_at = now()
        WHERE id = :id
        """
    )
    suspend fun storeMusicBrainzVerdict(
        id: Long,
        match: String,
        mbid: String?
    ): Int
}

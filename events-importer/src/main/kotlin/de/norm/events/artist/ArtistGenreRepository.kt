package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * The queries on `artist.wikidata_genres` (V265, ADR-060) and `artist.wikidata_read_at` (V266). The column is not on [ArtistEntity], so an
 * admin `save` never overwrites it, and only these queries and the genre derivation read or write it.
 */
interface ArtistGenreRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /**
     * The EXACT rows with a Wikidata link whose genres nobody has read, then those read before
     * [staleBefore], the oldest first; V266's partial index serves both. An act billed on an upcoming
     * night comes first, so the backfill tags those soonest.
     */
    @Query(
        """
        SELECT a.* FROM $EVENTS_SCHEMA.artist a
        WHERE a.musicbrainz_match = 'EXACT' AND a.wikidata_url IS NOT NULL
          AND (a.wikidata_genres IS NULL OR a.wikidata_read_at IS NULL OR a.wikidata_read_at < :staleBefore)
        ORDER BY a.wikidata_genres IS NULL DESC,
                 a.wikidata_read_at NULLS FIRST,
                 EXISTS (
                     SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea
                     JOIN $EVENTS_SCHEMA.event e ON e.id = ea.event_id
                     WHERE ea.artist_id = a.id AND e.event_date >= CURRENT_DATE
                 ) DESC,
                 a.id
        LIMIT :limit
        """
    )
    fun findGenreReads(
        limit: Int,
        staleBefore: Instant
    ): Flow<ArtistEntity>

    /** The EXACT rows that hold a genre, for `/genre-cleanup` to list the items the map lacks (#5252). */
    @Query(
        """
        SELECT a.id, a.slug, a.name, a.wikidata_genres FROM $EVENTS_SCHEMA.artist a
        WHERE a.musicbrainz_match = 'EXACT' AND cardinality(a.wikidata_genres) > 0
        ORDER BY a.id
        """
    )
    fun findWithGenres(): Flow<ArtistWikidataGenresResponse>

    /**
     * Stores the item ids Wikidata's P136 names, in statement order, and stamps `wikidata_read_at`.
     * The genre read runs last on the tick, so its stamp stands for both reads.
     */
    @Modifying
    @Query("UPDATE $EVENTS_SCHEMA.artist SET wikidata_genres = :genres, wikidata_read_at = now() WHERE id = :id")
    suspend fun storeGenres(
        id: Long,
        genres: Array<String>
    ): Int
}

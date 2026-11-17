package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * The queries on `artist.wikidata_genres` (V265, ADR-060). The column is not on [ArtistEntity], so an
 * admin `save` never overwrites it, and only these queries and the genre derivation read or write it.
 */
interface ArtistGenreRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /**
     * The EXACT rows with a Wikidata link whose genres nobody has read, served by V265's partial
     * index. An act billed on an upcoming night comes first, so the backfill tags those soonest.
     */
    @Query(
        """
        SELECT a.* FROM $EVENTS_SCHEMA.artist a
        WHERE a.musicbrainz_match = 'EXACT' AND a.wikidata_url IS NOT NULL AND a.wikidata_genres IS NULL
        ORDER BY EXISTS (
                     SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea
                     JOIN $EVENTS_SCHEMA.event e ON e.id = ea.event_id
                     WHERE ea.artist_id = a.id AND e.event_date >= CURRENT_DATE
                 ) DESC,
                 a.id
        LIMIT :limit
        """
    )
    fun findUnreadGenres(limit: Int): Flow<ArtistEntity>

    /** Stores the item ids Wikidata's P136 names, in statement order, and touches nothing else. */
    @Modifying
    @Query("UPDATE $EVENTS_SCHEMA.artist SET wikidata_genres = :genres WHERE id = :id")
    suspend fun storeGenres(
        id: Long,
        genres: Array<String>
    ): Int
}

package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * The queries on `artist.comedian` (V094, ADR-039). The column is not on [ArtistEntity], so an
 * admin `save` never overwrites it, and only these queries read or write it.
 */
interface ArtistOccupationRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /**
     * The EXACT rows with a Wikidata link whose occupation nobody has read, served by V094's partial
     * index. An act billed on an upcoming night comes first, so the backfill retypes those soonest.
     */
    @Query(
        """
        SELECT a.* FROM $EVENTS_SCHEMA.artist a
        WHERE a.musicbrainz_match = 'EXACT' AND a.wikidata_url IS NOT NULL AND a.comedian IS NULL
        ORDER BY EXISTS (
                     SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea
                     JOIN $EVENTS_SCHEMA.event e ON e.id = ea.event_id
                     WHERE ea.artist_id = a.id AND e.event_date >= CURRENT_DATE
                 ) DESC,
                 a.id
        LIMIT :limit
        """
    )
    fun findUnreadOccupations(limit: Int): Flow<ArtistEntity>

    /** Stores what Wikidata's occupations say, and touches nothing else. */
    @Modifying
    @Query("UPDATE $EVENTS_SCHEMA.artist SET comedian = :comedian WHERE id = :id")
    suspend fun storeComedian(
        id: Long,
        comedian: Boolean
    ): Int

    /** The subset of [slugs] whose artist Wikidata names a comedian. */
    @Query("SELECT slug FROM $EVENTS_SCHEMA.artist WHERE slug IN (:slugs) AND comedian")
    fun findComedianSlugs(slugs: Collection<String>): Flow<String>
}

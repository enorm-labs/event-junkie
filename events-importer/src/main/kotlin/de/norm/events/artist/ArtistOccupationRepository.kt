package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * The queries on `artist.comedian` (V094, ADR-039). The column is not on [ArtistEntity], so an
 * admin `save` never overwrites it, and only these queries read or write it. `wikidata_read_at`
 * (V266) is the genre read's stamp, see [ArtistGenreRepository.storeGenres].
 */
interface ArtistOccupationRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /**
     * The EXACT rows with a Wikidata link whose occupation nobody has read, then those whose item was
     * read before [staleBefore], the oldest first; V266's partial index serves both. A stale row waits
     * until its genres are read, so a row the genre read has not stamped yet is not read twice. An act
     * billed on an upcoming night comes first, so the backfill retypes those soonest.
     */
    @Query(
        """
        SELECT a.* FROM $EVENTS_SCHEMA.artist a
        WHERE a.musicbrainz_match = 'EXACT' AND a.wikidata_url IS NOT NULL
          AND (a.comedian IS NULL
               OR (a.wikidata_genres IS NOT NULL
                   AND (a.wikidata_read_at IS NULL OR a.wikidata_read_at < :staleBefore)))
        ORDER BY a.comedian IS NULL DESC,
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
    fun findOccupationReads(
        limit: Int,
        staleBefore: Instant
    ): Flow<ArtistEntity>

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

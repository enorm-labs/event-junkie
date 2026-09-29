package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * The Discogs verdict's queries on the `artist` table (#2026, ADR-035): its own interface, so that
 * [ArtistRepository] keeps the MusicBrainz half and stays under detekt's function cap. Same entity,
 * same table.
 */
interface ArtistDiscogsRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /**
     * The rows the Discogs sweep still owes a verdict, among [ids]: MusicBrainz knows no such
     * artist, and Discogs was never asked or the row was renamed since (#2026).
     */
    @Query(
        """
        SELECT * FROM $EVENTS_SCHEMA.artist
        WHERE id IN (:ids)
          AND musicbrainz_match = 'NONE'
          AND (discogs_match = 'UNCHECKED' OR updated_at > discogs_checked_at)
        ORDER BY id
        """
    )
    fun findNeedingDiscogsLookup(ids: Collection<Long>): Flow<ArtistEntity>

    /** The oldest NONE rows Discogs was never asked about — the backfill's slice, served by the partial index of V061. */
    @Query("SELECT * FROM $EVENTS_SCHEMA.artist WHERE musicbrainz_match = 'NONE' AND discogs_match = 'UNCHECKED' ORDER BY id LIMIT :limit")
    fun findUncheckedByDiscogs(limit: Int): Flow<ArtistEntity>

    /** How many NONE rows Discogs has not been asked about; the gauge that shows the backfill draining. */
    @Query("SELECT count(*) FROM $EVENTS_SCHEMA.artist WHERE musicbrainz_match = 'NONE' AND discogs_match = 'UNCHECKED'")
    suspend fun countUncheckedByDiscogs(): Long

    /**
     * Stores one Discogs verdict, and on EXACT fills `discogs_url` when it is empty. Nothing else.
     *
     * An empty link is filled and a present one is kept, the rule #1319 set for promoter websites
     * and step C follows. A row that loses its EXACT verdict on a later lookup also loses the link
     * that verdict wrote, and only that one: the link is cleared when it is the old id's page. The
     * right-hand side reads the row as it was before this update. `now()` in SQL for the reason
     * [ArtistRepository.storeMusicBrainzVerdict] gives.
     */
    @Modifying
    @Query(
        """
        UPDATE $EVENTS_SCHEMA.artist
        SET discogs_match = :match, discogs_id = :discogsId, discogs_checked_at = now(),
            discogs_url = CASE
                WHEN :match = 'EXACT' THEN COALESCE(discogs_url, :discogsUrl)
                WHEN discogs_id IS NOT NULL AND discogs_url LIKE 'https://www.discogs.com/artist/' || discogs_id || '%' THEN NULL
                ELSE discogs_url
            END
        WHERE id = :id
        """
    )
    suspend fun storeDiscogsVerdict(
        id: Long,
        match: String,
        discogsId: Long?,
        discogsUrl: String?
    ): Int
}

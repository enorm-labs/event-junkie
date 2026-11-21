package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * The MusicBrainz artists a person rejected as an artist row's match (V271). The lookup sweep never
 * picks a rejected id for that row again. A class, not derived queries, because `ArtistRepository`
 * is at detekt's function cap.
 */
@Repository
class MusicBrainzRejectionStore(
    private val databaseClient: DatabaseClient
) {
    /** The MBIDs rejected for artist [artistId]. */
    suspend fun rejectedIds(artistId: Long): Set<String> =
        databaseClient
            .sql("SELECT musicbrainz_id FROM $EVENTS_SCHEMA.artist_musicbrainz_rejection WHERE artist_id = :artistId")
            .bind("artistId", artistId)
            .map { row, _ -> requireNotNull(row.get("musicbrainz_id", String::class.java)) }
            .all()
            .asFlow()
            .toList()
            .toSet()

    /** Records [mbid] as rejected for artist [artistId]. An id already rejected stays as it is. */
    suspend fun add(
        artistId: Long,
        mbid: String
    ) {
        databaseClient
            .sql(
                "INSERT INTO $EVENTS_SCHEMA.artist_musicbrainz_rejection (artist_id, musicbrainz_id) VALUES (:artistId, :mbid) " +
                    "ON CONFLICT DO NOTHING"
            ).bind("artistId", artistId)
            .bind("mbid", mbid)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Removes the rejection of [mbid] for artist [artistId], as when a person picks that id after all. */
    suspend fun remove(
        artistId: Long,
        mbid: String
    ): Boolean =
        databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.artist_musicbrainz_rejection WHERE artist_id = :artistId AND musicbrainz_id = :mbid")
            .bind("artistId", artistId)
            .bind("mbid", mbid)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /**
     * Clears the match of artist [artistId] and everything the enrichment copied from it, and sets AMBIGUOUS (V271).
     *
     * AMBIGUOUS, not NONE: NONE sends the row to the Discogs sweep, which can match the same namesake there.
     * `musicbrainz_checked_at = now()` keeps the row out of the lookup sweep until a rename. A licensed text and a
     * Commons photo came from the match; a venue's own text has no credit and stays. The links and facts cannot
     * tell where they came from, so all of them go.
     */
    suspend fun clearMatch(artistId: Long) {
        databaseClient
            .sql(
                """
                UPDATE $EVENTS_SCHEMA.artist
                SET musicbrainz_id = NULL,
                    musicbrainz_match = 'AMBIGUOUS',
                    musicbrainz_checked_at = now(),
                    musicbrainz_enriched_at = NULL,
                    description = CASE WHEN description_attribution IS NULL THEN description END,
                    description_language = CASE WHEN description_attribution IS NULL THEN description_language END,
                    description_attribution = NULL,
                    description_licence_id = NULL,
                    description_source_url = NULL,
                    description_alt = NULL,
                    description_alt_language = NULL,
                    description_alt_attribution = NULL,
                    description_alt_licence_id = NULL,
                    description_alt_source_url = NULL,
                    image_url = CASE WHEN $FROM_COMMONS THEN NULL ELSE image_url END,
                    image_attribution = CASE WHEN $FROM_COMMONS THEN NULL ELSE image_attribution END,
                    image_licence_id = CASE WHEN $FROM_COMMONS THEN NULL ELSE image_licence_id END,
                    image_source_url = CASE WHEN $FROM_COMMONS THEN NULL ELSE image_source_url END,
                    website_url = NULL,
                    facebook_url = NULL,
                    instagram_url = NULL,
                    youtube_url = NULL,
                    bandcamp_url = NULL,
                    soundcloud_url = NULL,
                    discogs_url = NULL,
                    wikidata_url = NULL,
                    resident_advisor_url = NULL,
                    spotify_url = NULL,
                    artist_type = NULL,
                    founded = NULL,
                    founded_in = NULL,
                    country = NULL,
                    comedian = NULL,
                    wikidata_genres = NULL,
                    wikidata_read_at = NULL,
                    discogs_id = NULL,
                    discogs_match = 'UNCHECKED',
                    discogs_checked_at = NULL
                WHERE id = :artistId
                """.trimIndent()
            ).bind("artistId", artistId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private companion object {
        const val FROM_COMMONS = "image_source_url LIKE 'https://commons.wikimedia.org/%'"
    }
}

package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Deletes the artist rows no event bills (#350). A class, not a derived query, because
 * `ArtistRepository` is at detekt's function cap.
 */
@Repository
class UnbilledArtistStore(
    private val databaseClient: DatabaseClient
) {
    /**
     * Deletes the rows no event bills, created before [createdBefore], and returns how many. A row
     * with an exact MusicBrainz match or any profile field stays: that is what enrichment or an admin
     * wrote, and it names a real act the next billing reuses.
     */
    suspend fun deleteUnbilled(createdBefore: Instant): Long =
        databaseClient
            .sql(
                """
                DELETE FROM $EVENTS_SCHEMA.artist a
                WHERE NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_artist ea WHERE ea.artist_id = a.id)
                  AND a.created_at < :createdBefore
                  AND a.musicbrainz_match <> 'EXACT'
                  AND COALESCE(
                        a.description, a.image_url, a.website_url, a.facebook_url, a.instagram_url, a.youtube_url,
                        a.bandcamp_url, a.soundcloud_url, a.discogs_url, a.wikidata_url, a.resident_advisor_url, a.spotify_url
                      ) IS NULL
                """.trimIndent()
            ).bind("createdBefore", createdBefore)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
}

package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * Writes what the MusicBrainz enrichment filled, and only that (ADR-031, step C).
 *
 * One UPDATE over the columns the sweep decided on, never a `save`: `save` writes every column and
 * the name is never rewritten from MusicBrainz. The column names come from [COLUMNS], so the SQL is
 * assembled from a fixed vocabulary and every value is bound.
 *
 * **Both timestamps are `now()` in the same statement, on purpose.** `trg_artist_updated_at` moves
 * `updated_at` on this UPDATE too, and `ArtistRepository.findNeedingMusicBrainzLookup` reads
 * `updated_at > musicbrainz_checked_at` as "renamed since". Left alone, every enrichment queued its
 * row for a lookup, whose fresh `checked_at` queued it for an enrichment, one round trip per import
 * forever. Equal timestamps end that; the verdict itself is not touched.
 */
@Repository
class ArtistEnrichmentStore(
    private val databaseClient: DatabaseClient
) {
    /** Sets [columns] on the row [id] and stamps it enriched. Rows updated, one or zero. */
    suspend fun store(
        id: Long,
        columns: Map<String, String>
    ): Long {
        val unknown = columns.keys - COLUMNS
        require(unknown.isEmpty()) { "Not an enrichment column: $unknown" }
        val assignments = columns.keys.joinToString("") { "$it = :$it, " }
        var spec =
            databaseClient
                .sql(
                    """
                    UPDATE $EVENTS_SCHEMA.artist
                    SET ${assignments}musicbrainz_enriched_at = now(), musicbrainz_checked_at = now()
                    WHERE id = :id
                    """.trimIndent()
                ).bind("id", id)
        columns.forEach { (column, value) -> spec = spec.bind(column, value) }
        return spec.fetch().rowsUpdated().awaitSingle()
    }

    companion object {
        /** Every column the enrichment may write; the four image columns, and each text with its credit, are written together or not at all. */
        val COLUMNS =
            setOf(
                "website_url",
                "facebook_url",
                "instagram_url",
                "youtube_url",
                "bandcamp_url",
                "soundcloud_url",
                "discogs_url",
                "wikidata_url",
                "resident_advisor_url",
                "spotify_url",
                "artist_type",
                "founded",
                "founded_in",
                "country",
                "image_url",
                "image_attribution",
                "image_licence_id",
                "image_source_url",
                "description",
                "description_language",
                "description_attribution",
                "description_licence_id",
                "description_source_url",
                "description_alt",
                "description_alt_language",
                "description_alt_attribution",
                "description_alt_licence_id",
                "description_alt_source_url"
            )
    }
}

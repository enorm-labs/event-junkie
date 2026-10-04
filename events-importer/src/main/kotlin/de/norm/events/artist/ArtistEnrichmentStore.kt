package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * Writes what the MusicBrainz enrichment filled, and only that (ADR-031, step C).
 *
 * One UPDATE over the columns the sweep decided on, never a `save`: `save` writes every column, and
 * the name changes only in letter case, when [ArtistEnrichment][de.norm.events.scraper.ArtistEnrichment]
 * says so (#2317). The column names come from [COLUMNS], so the SQL is assembled from a fixed
 * vocabulary and every value is bound.
 *
 * A name write moves `musicbrainz_checked_at` to the same `now()` as `name_changed_at` (V062).
 * Otherwise the rename queues a fresh lookup, and the fresh verdict a second read, of a row whose
 * folded name did not change.
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
        val assignments =
            columns.keys.joinToString("") { "$it = :$it, " } +
                if ("name" in columns) "musicbrainz_checked_at = now(), " else ""
        var spec =
            databaseClient
                .sql(
                    """
                    UPDATE $EVENTS_SCHEMA.artist
                    SET ${assignments}musicbrainz_enriched_at = now()
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
                "name",
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

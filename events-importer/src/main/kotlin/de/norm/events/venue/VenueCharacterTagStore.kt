package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** One row of `venue_character_tag`: a tag and the venue's own page that states it. */
data class VenueCharacterTagRow(
    val tag: String,
    val sourceUrl: String
)

/**
 * Reads and writes `venue_character_tag` (V099). Raw SQL, because the table's key is the pair
 * `(venue_id, tag)` and Spring Data R2DBC maps no composite key.
 */
@Repository
class VenueCharacterTagStore(
    private val databaseClient: DatabaseClient
) {
    /** The venue's tags, in no particular order. */
    suspend fun findByVenueId(venueId: Long): List<VenueCharacterTagRow> =
        databaseClient
            .sql("SELECT tag, source_url FROM $EVENTS_SCHEMA.venue_character_tag WHERE venue_id = :venueId")
            .bind("venueId", venueId)
            .map { row: Readable -> row.toTagRow() }
            .all()
            .collectList()
            .awaitSingle()

    /** Sets [tag] on the venue, or replaces the source URL of a tag already set. */
    suspend fun upsert(
        venueId: Long,
        tag: String,
        sourceUrl: String
    ): VenueCharacterTagRow =
        databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.venue_character_tag (venue_id, tag, source_url) VALUES (:venueId, :tag, :sourceUrl)
                ON CONFLICT (venue_id, tag) DO UPDATE SET source_url = EXCLUDED.source_url
                RETURNING tag, source_url
                """.trimIndent()
            ).bind("venueId", venueId)
            .bind("tag", tag)
            .bind("sourceUrl", sourceUrl)
            .map { row: Readable -> row.toTagRow() }
            .one()
            .awaitSingle()

    /** Removes [tag] from the venue; `true` when a row was there to remove. */
    suspend fun delete(
        venueId: Long,
        tag: String
    ): Boolean =
        databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.venue_character_tag WHERE venue_id = :venueId AND tag = :tag")
            .bind("venueId", venueId)
            .bind("tag", tag)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    private fun Readable.toTagRow() =
        VenueCharacterTagRow(
            tag = requireNotNull(get("tag", String::class.java)) { "venue_character_tag.tag is NOT NULL" },
            sourceUrl = requireNotNull(get("source_url", String::class.java)) { "venue_character_tag.source_url is NOT NULL" }
        )
}

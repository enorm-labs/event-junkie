package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** One character tag on a venue and the venue's own page that states it (#2379). */
data class VenueCharacterTagRow(
    val venueId: Long,
    val tag: String,
    val sourceUrl: String
)

/**
 * Reads `venue_character_tag`, which the importer owns. Raw SQL, because the table's key is the pair
 * `(venue_id, tag)` and Spring Data R2DBC maps no composite key.
 */
@Repository
class VenueCharacterTagRepository(
    private val databaseClient: DatabaseClient
) {
    /** The tags of every venue in [venueIds], keyed by venue, each list ordered by tag. A venue without tags is absent. */
    suspend fun findByVenueIds(venueIds: Collection<Long>): Map<Long, List<VenueCharacterTagRow>> {
        if (venueIds.isEmpty()) return emptyMap()
        return databaseClient
            .sql(
                "SELECT venue_id, tag, source_url FROM $EVENTS_SCHEMA.venue_character_tag " +
                    "WHERE venue_id IN (:venueIds) ORDER BY venue_id, tag"
            ).bind("venueIds", venueIds)
            .map { row: Readable ->
                VenueCharacterTagRow(
                    venueId = requireNotNull(row.get("venue_id", Long::class.javaObjectType)) { "venue_character_tag.venue_id is NOT NULL" },
                    tag = requireNotNull(row.get("tag", String::class.java)) { "venue_character_tag.tag is NOT NULL" },
                    sourceUrl = requireNotNull(row.get("source_url", String::class.java)) { "venue_character_tag.source_url is NOT NULL" }
                )
            }.all()
            .collectList()
            .awaitSingle()
            .groupBy { it.venueId }
    }
}

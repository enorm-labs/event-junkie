package de.norm.events.instagram

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/** A venue whose Instagram account is read: a row of `instagram_venue`. */
data class InstagramVenue(
    val id: Long,
    val slug: String,
    val name: String,
    val instagramUrl: String?
)

/** The `instagram_venue` table: which venues the pass reads. The handle comes from `venue.instagram_url`. */
@Repository
class InstagramVenueRepository(
    private val template: R2dbcEntityTemplate
) {
    suspend fun findVenues(): List<InstagramVenue> =
        template.databaseClient
            .sql(
                "SELECT v.id, v.slug, v.name, v.instagram_url FROM $EVENTS_SCHEMA.instagram_venue iv " +
                    "JOIN $EVENTS_SCHEMA.venue v ON v.id = iv.venue_id ORDER BY v.slug"
            ).map { row, _ -> row.toVenue() }
            .flow()
            .toList()

    /** Switches [venueId] on. A venue already on stays as it is. */
    suspend fun switchOn(venueId: Long): Long =
        template.databaseClient
            .sql("INSERT INTO $EVENTS_SCHEMA.instagram_venue (venue_id) VALUES (:venueId) ON CONFLICT DO NOTHING")
            .bind("venueId", venueId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** Switches [venueId] off and deletes every draft of it (ADR-048 rule 8). Returns the drafts deleted. */
    suspend fun switchOff(venueId: Long): Long {
        val drafts =
            template.databaseClient
                .sql("DELETE FROM $EVENTS_SCHEMA.instagram_draft WHERE venue_id = :venueId")
                .bind("venueId", venueId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        template.databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.instagram_venue WHERE venue_id = :venueId")
            .bind("venueId", venueId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return drafts
    }

    /** Any venue by its slug, switched on or not. */
    suspend fun findVenueBySlug(slug: String): InstagramVenue? =
        template.databaseClient
            .sql("SELECT id, slug, name, instagram_url FROM $EVENTS_SCHEMA.venue WHERE slug = :slug")
            .bind("slug", slug)
            .map { row, _ -> row.toVenue() }
            .one()
            .awaitSingleOrNull()

    private fun Readable.toVenue(): InstagramVenue =
        InstagramVenue(
            id = required("id", Number::class.java).toLong(),
            slug = required("slug", String::class.java),
            name = required("name", String::class.java),
            instagramUrl = get("instagram_url", String::class.java)
        )

    private fun <T : Any> Readable.required(
        column: String,
        type: Class<T>
    ): T = requireNotNull(get(column, type)) { "Column '$column' is missing from the venue projection" }
}

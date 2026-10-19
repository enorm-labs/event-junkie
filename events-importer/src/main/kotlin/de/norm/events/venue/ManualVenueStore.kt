package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/** One venue on the "Manual venues" list: open, and no event source points at it. */
@Schema(description = "An open venue without an importer, whose new events a person enters by hand")
data class ManualVenueResponse(
    @Schema(description = "Database ID of the venue.", example = "42")
    val id: Long,
    @Schema(description = "Slug of the venue.", example = "funkloch")
    val slug: String,
    @Schema(description = "Name of the venue.", example = "Funkloch")
    val name: String,
    @Schema(description = "Where the venue publishes its programme; never fetched.", example = "https://funkloch.berlin/programm")
    val programmeUrl: String?,
    @Schema(description = "When a person last read the programme; null when nobody has.")
    val programmeCheckedAt: Instant?,
    @Schema(description = "When the newest event at the venue was entered; null when it has none.")
    val newestEventCreatedAt: Instant?
)

/**
 * The venues whose events a person enters by hand (#2993), with raw SQL over `event_source` and `event`, so the venue
 * module still depends on no other (see [VenueProgrammeStore]).
 */
@Repository
class ManualVenueStore(
    private val databaseClient: DatabaseClient
) {
    /** Open venues no event source points at: the never-checked first, then the oldest check, then by name. */
    suspend fun findManual(): List<ManualVenueResponse> =
        databaseClient
            .sql(
                """
                SELECT v.id, v.slug, v.name, v.programme_url, v.programme_checked_at,
                       (SELECT max(e.created_at) FROM $EVENTS_SCHEMA.event e WHERE e.venue_id = v.id) AS newest_event_created_at
                FROM $EVENTS_SCHEMA.venue v
                WHERE v.closed_on IS NULL
                  AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_source es WHERE es.venue_id = v.id)
                ORDER BY v.programme_checked_at NULLS FIRST, v.name, v.id
                """.trimIndent()
            ).map { row, _ ->
                ManualVenueResponse(
                    id = requireNotNull(row.get("id", Number::class.java)).toLong(),
                    slug = row.string("slug"),
                    name = row.string("name"),
                    programmeUrl = row.get("programme_url", String::class.java),
                    programmeCheckedAt = row.get("programme_checked_at", Instant::class.java),
                    newestEventCreatedAt = row.get("newest_event_created_at", Instant::class.java)
                )
            }.flow()
            .toList()

    /** Sets `programme_checked_at` of venue [id] to now. Returns false when no venue has that id. */
    suspend fun markProgrammeChecked(id: Long): Boolean =
        databaseClient
            .sql("UPDATE $EVENTS_SCHEMA.venue SET programme_checked_at = now() WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    private fun Readable.string(name: String): String = requireNotNull(get(name, String::class.java))
}

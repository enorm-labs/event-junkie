package de.norm.events.genretag

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/** Deletes the genre tags no event links (#4923), the genre side of `UncreditedPromoterStore`. */
@Repository
class UnlinkedGenreTagStore(
    private val databaseClient: DatabaseClient
) {
    /**
     * Deletes the tags no event links, created before [createdBefore], and returns their slugs. A past event's link
     * and a derived link both keep a tag: the past event's page still shows it.
     */
    suspend fun deleteUnlinked(createdBefore: Instant): List<String> =
        databaseClient
            .sql(
                """
                DELETE FROM $EVENTS_SCHEMA.genre_tag t
                WHERE NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_genre_tag x WHERE x.genre_tag_id = t.id)
                  AND t.created_at < :createdBefore
                RETURNING t.slug
                """.trimIndent()
            ).bind("createdBefore", createdBefore)
            .map { row, _ -> row.get("slug", String::class.java).orEmpty() }
            .flow()
            .toList()
            .sorted()
}

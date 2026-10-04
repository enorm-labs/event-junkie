package de.norm.events.promoter

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/** Deletes the promoter rows no event credits (#2653), the promoter side of `UnbilledArtistStore`. */
@Repository
class UncreditedPromoterStore(
    private val databaseClient: DatabaseClient
) {
    /**
     * Deletes the rows no event credits, created before [createdBefore], and returns their slugs. A
     * row with a description or an image stays: a person wrote that, and an import cannot restore it.
     */
    suspend fun deleteUncredited(createdBefore: Instant): List<String> =
        databaseClient
            .sql(
                """
                DELETE FROM $EVENTS_SCHEMA.promoter p
                WHERE NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.event_promoter ep WHERE ep.promoter_id = p.id)
                  AND p.created_at < :createdBefore
                  AND p.description IS NULL
                  AND p.image_url IS NULL
                RETURNING p.slug
                """.trimIndent()
            ).bind("createdBefore", createdBefore)
            .map { row, _ -> row.get("slug", String::class.java).orEmpty() }
            .flow()
            .toList()
            .sorted()
}

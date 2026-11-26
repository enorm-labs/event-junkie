package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * Writes `event_slug_alias`: the slugs an event had before its title, date or venue changed (#5560).
 * The BFF answers an old link through them. The import and the admin edit call it for every row they
 * write.
 */
@Repository
class EventSlugAliasStore(
    private val databaseClient: DatabaseClient
) {
    /**
     * Keeps the stored slug of each [written] row whose slug changed, as an alias of that row's id, then
     * deletes every alias that a [written] row now carries as its live slug. A slug already kept moves
     * to the event that last had it.
     *
     * @param written the rows just saved, new ones included.
     * @param storedSlugs the slug each written row had before, by id. A new row has none.
     * @return how many slugs were kept as aliases.
     */
    suspend fun keep(
        written: Collection<EventEntity>,
        storedSlugs: Map<Long, String>
    ): Int {
        if (written.isEmpty()) return 0
        val renamed =
            written.mapNotNull { row ->
                val id = row.id ?: return@mapNotNull null
                storedSlugs[id]?.takeIf { it != row.slug }?.let { it to id }
            }
        if (renamed.isNotEmpty()) {
            databaseClient
                .sql(
                    "INSERT INTO $EVENTS_SCHEMA.event_slug_alias (slug, event_id) " +
                        "SELECT * FROM unnest(:slugs::text[], :ids::bigint[]) " +
                        "ON CONFLICT (slug) DO UPDATE SET event_id = EXCLUDED.event_id"
                ).bind("slugs", renamed.map { it.first }.toTypedArray())
                .bind("ids", renamed.map { it.second }.toTypedArray())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        // After the insert, so a slug one row left and another row took in the same write stays live only.
        databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.event_slug_alias WHERE slug = ANY(:slugs::text[])")
            .bind("slugs", written.map { it.slug }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return renamed.size
    }
}

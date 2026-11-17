package de.norm.events.account

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/** A name visitors watch an act under, and how many of them do. */
data class WatchedName(
    val name: String,
    val watchers: Int
)

/**
 * How many accounts watch an act before it has an artist row (#5214): the `kind = 'watch'` rows of `accounts.follow`, which
 * the BFF writes (V259). Counts only, never which account watches, so the admin review can put a watched act first.
 * Qualified names, because the connection's `search_path` is `events`.
 */
@Repository
class WatchCounts(
    private val databaseClient: DatabaseClient
) {
    /** The name MusicBrainz gave each watched act, with its count. */
    suspend fun byName(): List<WatchedName> =
        databaseClient
            .sql("SELECT name, count(*) AS watchers FROM accounts.follow WHERE kind = 'watch' GROUP BY name")
            .map { row, _ -> WatchedName(row.required("name", String::class.java), row.required("watchers", Long::class.javaObjectType).toInt()) }
            .flow()
            .toList()

    /** How many accounts watch each of [mbids]; an id nobody watches is absent. */
    suspend fun byMbid(mbids: Collection<String>): Map<String, Int> {
        if (mbids.isEmpty()) return emptyMap()
        return databaseClient
            .sql("SELECT mbid, count(*) AS watchers FROM accounts.follow WHERE kind = 'watch' AND mbid = ANY(:mbids) GROUP BY mbid")
            .bind("mbids", mbids.toTypedArray())
            .map { row, _ -> row.required("mbid", String::class.java) to row.required("watchers", Long::class.javaObjectType).toInt() }
            .flow()
            .toList()
            .toMap()
    }
}

private fun <T : Any> Readable.required(
    column: String,
    type: Class<T>
): T = requireNotNull(get(column, type)) { "Column '$column' is missing from the watch count" }

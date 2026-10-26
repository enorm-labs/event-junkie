package de.norm.events.account

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** A saved search (#3890): the events list's query string and the name the visitor gave it. */
data class SavedSearch(
    val id: UUID,
    val name: String,
    val query: String,
    val savedAt: Instant
)

/**
 * The `kind = 'search'` rows of `accounts.follow` (V195, V201). Apart from [FollowStore], whose rows point at an entity in
 * `events`; a search points at nothing and needs no join.
 */
@Component
class SavedSearchStore(
    private val db: DatabaseClient
) {
    suspend fun list(accountId: UUID): List<SavedSearch> =
        db
            .sql("SELECT $COLUMNS FROM accounts.follow WHERE account_id = :account AND kind = 'search' ORDER BY lower(name), created_at")
            .bind("account", accountId)
            .map(::toSearch)
            .flow()
            .toList()

    suspend fun count(accountId: UUID): Long =
        db
            .sql("SELECT count(*) AS n FROM accounts.follow WHERE account_id = :account AND kind = 'search'")
            .bind("account", accountId)
            .map { row -> row.required("n", Long::class.javaObjectType) }
            .awaitSingle()

    /** Saves [query], or answers the search that already holds it, so saving the same filters twice finds the first. */
    suspend fun save(
        accountId: UUID,
        name: String,
        query: String,
        now: Instant
    ): SavedSearch =
        try {
            db
                .sql(
                    "INSERT INTO accounts.follow (account_id, kind, query, name, created_at) VALUES (:account, 'search', :query, :name, :now) " +
                        "RETURNING $COLUMNS"
                ).bind("account", accountId)
                .bind("query", query)
                .bind("name", name)
                .bind("now", now)
                .map(::toSearch)
                .awaitSingle()
        } catch (_: DuplicateKeyException) {
            byQuery(accountId, query)
        }

    /** The renamed search, or null when the account has no search [id]. */
    suspend fun rename(
        accountId: UUID,
        id: UUID,
        name: String
    ): SavedSearch? =
        db
            .sql("UPDATE accounts.follow SET name = :name WHERE id = :id AND account_id = :account AND kind = 'search' RETURNING $COLUMNS")
            .bind("name", name)
            .bind("id", id)
            .bind("account", accountId)
            .map(::toSearch)
            .awaitOneOrNull()

    /** Whether a search was deleted. Another account's id deletes nothing. */
    suspend fun delete(
        accountId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM accounts.follow WHERE id = :id AND account_id = :account AND kind = 'search'")
            .bind("id", id)
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated() > 0

    private suspend fun byQuery(
        accountId: UUID,
        query: String
    ): SavedSearch =
        db
            .sql("SELECT $COLUMNS FROM accounts.follow WHERE account_id = :account AND kind = 'search' AND query = :query")
            .bind("account", accountId)
            .bind("query", query)
            .map(::toSearch)
            .awaitSingle()

    private companion object {
        const val COLUMNS = "id, name, query, created_at"

        fun toSearch(row: Readable) =
            SavedSearch(
                id = row.required("id", UUID::class.java),
                name = row.required("name", String::class.java),
                query = row.required("query", String::class.java),
                savedAt = row.required("created_at", Instant::class.java)
            )
    }
}

package de.norm.events.account

import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.stereotype.Repository
import java.time.Instant

/** Whether new accounts can be created, and since when (V200). */
data class SignUpState(
    val open: Boolean,
    val changedAt: Instant
)

/** The one row of `accounts.sign_up` (V200). Qualified, because the connection's `search_path` is `events`. */
@Repository
class SignUpStore(
    private val databaseClient: DatabaseClient
) {
    suspend fun read(): SignUpState =
        databaseClient
            .sql("SELECT open, changed_at FROM accounts.sign_up")
            .map { row, _ -> toState(row) }
            .awaitSingle()

    /** Sets the switch. `changed_at` moves only when the value does, so a repeated call keeps the time of the real change. */
    suspend fun set(open: Boolean): SignUpState =
        databaseClient
            .sql(
                "UPDATE accounts.sign_up SET changed_at = CASE WHEN open = :open THEN changed_at ELSE now() END, open = :open " +
                    "RETURNING open, changed_at"
            ).bind("open", open)
            .map { row, _ -> toState(row) }
            .awaitSingle()

    private fun toState(row: Readable) =
        SignUpState(
            open = requireNotNull(row.get("open", Boolean::class.javaObjectType)),
            changedAt = requireNotNull(row.get("changed_at", Instant::class.java))
        )
}

package de.norm.events.account

import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** The rows of `accounts.session` (V193), keyed by the SHA-256 of the cookie's token. */
@Component
class SessionStore(
    private val db: DatabaseClient
) {
    suspend fun createSession(
        tokenHash: String,
        accountId: UUID,
        now: Instant,
        expiresAt: Instant
    ) {
        db
            .sql(
                "INSERT INTO accounts.session (token_hash, account_id, created_at, last_seen_at, expires_at) " +
                    "VALUES (:hash, :account, :now, :now, :expires)"
            ).bind("hash", tokenHash)
            .bind("account", accountId)
            .bind("now", now)
            .bind("expires", expiresAt)
            .await()
    }

    /** The account behind a live session, with the session's last-seen time. */
    suspend fun findSession(
        tokenHash: String,
        now: Instant
    ): Pair<Account, Instant>? =
        db
            .sql(
                "SELECT ${ACCOUNT_COLUMNS.split(", ").joinToString { "a.$it" }}, s.last_seen_at FROM accounts.session s " +
                    "JOIN accounts.account a ON a.id = s.account_id WHERE s.token_hash = :hash AND s.expires_at > :now"
            ).bind("hash", tokenHash)
            .bind("now", now)
            .map { row -> toAccount(row) to row.required("last_seen_at", Instant::class.java) }
            .awaitOneOrNull()

    suspend fun extendSession(
        tokenHash: String,
        now: Instant,
        expiresAt: Instant
    ) {
        db
            .sql("UPDATE accounts.session SET last_seen_at = :now, expires_at = :expires WHERE token_hash = :hash")
            .bind("hash", tokenHash)
            .bind("now", now)
            .bind("expires", expiresAt)
            .await()
    }

    suspend fun deleteSession(tokenHash: String) {
        db.sql("DELETE FROM accounts.session WHERE token_hash = :hash").bind("hash", tokenHash).await()
    }

    suspend fun deleteSessions(accountId: UUID): Long =
        db
            .sql("DELETE FROM accounts.session WHERE account_id = :account")
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated()

    /** Sessions that expired before [now]. A lookup already ignores them; this keeps the table from growing. */
    suspend fun deleteExpired(now: Instant): Long =
        db
            .sql("DELETE FROM accounts.session WHERE expires_at < :now")
            .bind("now", now)
            .fetch()
            .awaitRowsUpdated()

    suspend fun sessions(accountId: UUID): List<SessionInfo> =
        db
            .sql("SELECT created_at, last_seen_at, expires_at FROM accounts.session WHERE account_id = :account ORDER BY created_at")
            .bind("account", accountId)
            .map { row ->
                SessionInfo(
                    row.required("created_at", Instant::class.java),
                    row.required("last_seen_at", Instant::class.java),
                    row.required("expires_at", Instant::class.java)
                )
            }.flow()
            .toList()
}

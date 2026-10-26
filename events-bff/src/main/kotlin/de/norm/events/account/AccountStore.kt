package de.norm.events.account

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The accounts and their sign-in links in the `accounts` schema (V193); [SessionStore] has the sessions. Plain SQL, qualified, because the connection's
 * `search_path` is `events`. Addresses compare case-insensitively, as the unique index does.
 */
@Component
class AccountStore(
    private val db: DatabaseClient
) {
    suspend fun issueToken(
        tokenHash: String,
        email: String,
        locale: String,
        now: Instant,
        expiresAt: Instant
    ) {
        db
            .sql(
                "INSERT INTO accounts.one_time_token (token_hash, email, locale, created_at, expires_at) " +
                    "VALUES (:hash, :email, :locale, :now, :expires)"
            ).bind("hash", tokenHash)
            .bind("email", email)
            .bind("locale", locale)
            .bind("now", now)
            .bind("expires", expiresAt)
            .await()
    }

    /** How many links [email] asked for since [since], used or not. The per-address rate limit. */
    suspend fun linksSince(
        email: String,
        since: Instant
    ): Long =
        db
            .sql("SELECT count(*) AS n FROM accounts.one_time_token WHERE lower(email) = lower(:email) AND created_at > :since")
            .bind("email", email)
            .bind("since", since)
            .map { row -> row.required("n", Long::class.javaObjectType) }
            .awaitSingle()

    /** Whether [email] has an account. Only asked while sign-up is paused (#3906). */
    suspend fun exists(email: String): Boolean =
        db
            .sql("SELECT EXISTS (SELECT 1 FROM accounts.account WHERE lower(email) = lower(:email)) AS found")
            .bind("email", email)
            .map { row -> row.required("found", Boolean::class.javaObjectType) }
            .awaitSingle()

    /** Whether new accounts can be created: false while an operator has paused sign-up (V200, #3906). Read on every call, so a flip lands at once. */
    suspend fun signUpOpen(): Boolean =
        db
            .sql("SELECT open FROM accounts.sign_up")
            .map { row -> row.required("open", Boolean::class.javaObjectType) }
            .awaitOneOrNull() ?: true

    /** Marks the token used and answers its address and locale, or null if it is unknown, used or expired. One statement, so two tabs cannot both use it. */
    suspend fun consumeToken(
        tokenHash: String,
        now: Instant
    ): Pair<String, String>? =
        db
            .sql(
                "UPDATE accounts.one_time_token SET consumed_at = :now " +
                    "WHERE token_hash = :hash AND consumed_at IS NULL AND expires_at > :now RETURNING email, locale"
            ).bind("hash", tokenHash)
            .bind("now", now)
            .map { row -> row.required("email", String::class.java) to row.required("locale", String::class.java) }
            .awaitOneOrNull()

    /** Creates the account at its first sign-in, or records the sign-in and the latest locale. A sign-in cancels a pending deletion. */
    suspend fun signIn(
        email: String,
        locale: String,
        now: Instant
    ): Account =
        db
            .sql(
                "INSERT INTO accounts.account (email, locale, created_at, last_sign_in_at) VALUES (:email, :locale, :now, :now) " +
                    "ON CONFLICT ((lower(email))) DO UPDATE SET locale = EXCLUDED.locale, last_sign_in_at = EXCLUDED.last_sign_in_at, " +
                    "deletion_warned_at = NULL " +
                    "RETURNING $ACCOUNT_COLUMNS"
            ).bind("email", email)
            .bind("locale", locale)
            .bind("now", now)
            .map(::toAccount)
            .awaitSingle()

    /** The account, its sessions by cascade, and every sign-in link its address asked for. */
    suspend fun deleteAccount(account: Account) {
        db.sql("DELETE FROM accounts.account WHERE id = :id").bind("id", account.id).await()
        db.sql("DELETE FROM accounts.one_time_token WHERE lower(email) = lower(:email)").bind("email", account.email).await()
    }

    /**
     * Marks up to [limit] accounts without a sign-in since [inactiveSince] as warned, and answers them. Each row goes to one caller only, so
     * the BFF's replicas, which all run the cleanup, never warn one account twice.
     */
    suspend fun claimWarnings(
        inactiveSince: Instant,
        now: Instant,
        limit: Int
    ): List<Account> =
        db
            .sql(
                "UPDATE accounts.account SET deletion_warned_at = :now WHERE id IN (" +
                    "SELECT id FROM accounts.account WHERE last_sign_in_at < :since AND deletion_warned_at IS NULL " +
                    "ORDER BY last_sign_in_at LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING $ACCOUNT_COLUMNS"
            ).bind("now", now)
            .bind("since", inactiveSince)
            .bind("limit", limit)
            .map(::toAccount)
            .flow()
            .toList()

    /** Undoes a claim whose mail did not go out, so the next run warns again. A sign-in since then has already cleared it. */
    suspend fun releaseWarning(id: UUID) {
        db
            .sql("UPDATE accounts.account SET deletion_warned_at = NULL WHERE id = :id")
            .bind("id", id)
            .await()
    }

    /**
     * Deletes the accounts without a sign-in since [inactiveSince] that were warned before [warnedBefore], with their sign-in links. Both
     * conditions, so no account goes without a warning a month earlier, however late the job runs.
     */
    suspend fun deleteInactive(
        inactiveSince: Instant,
        warnedBefore: Instant
    ): Long =
        db
            .sql(
                "WITH gone AS (DELETE FROM accounts.account WHERE last_sign_in_at < :since AND deletion_warned_at < :warned RETURNING email), " +
                    "links AS (DELETE FROM accounts.one_time_token t USING gone WHERE lower(t.email) = lower(gone.email)) " +
                    "SELECT count(*) AS n FROM gone"
            ).bind("since", inactiveSince)
            .bind("warned", warnedBefore)
            .map { row -> row.required("n", Long::class.javaObjectType) }
            .awaitSingle()

    /** How many accounts exist, how many were used since [activeSince], and how many browsers hold a live session at [now]. */
    suspend fun counts(
        activeSince: Instant,
        now: Instant
    ): AccountCounts =
        db
            .sql(
                "SELECT (SELECT count(*) FROM accounts.account) AS total, " +
                    "(SELECT count(*) FROM accounts.account WHERE last_sign_in_at > :since) AS active, " +
                    "(SELECT count(*) FROM accounts.session WHERE expires_at > :now) AS sessions"
            ).bind("since", activeSince)
            .bind("now", now)
            .map { row ->
                AccountCounts(
                    total = row.required("total", Long::class.javaObjectType),
                    active = row.required("active", Long::class.javaObjectType),
                    sessions = row.required("sessions", Long::class.javaObjectType)
                )
            }.awaitSingle()

    /** Sign-in links that expired before [before], used or not. */
    suspend fun deleteLinks(before: Instant): Long =
        db
            .sql("DELETE FROM accounts.one_time_token WHERE expires_at < :before")
            .bind("before", before)
            .fetch()
            .awaitRowsUpdated()
}

data class AccountCounts(
    val total: Long,
    val active: Long,
    val sessions: Long
)

internal const val ACCOUNT_COLUMNS = "id, email, locale, created_at, last_sign_in_at"

/** A column the schema declares NOT NULL. A null is a schema change this code has not caught up with. */
internal fun <T : Any> Readable.required(
    name: String,
    type: Class<T>
): T = requireNotNull(get(name, type)) { "accounts column $name is null" }

internal fun toAccount(row: Readable) =
    Account(
        id = row.required("id", UUID::class.java),
        email = row.required("email", String::class.java),
        locale = row.required("locale", String::class.java),
        createdAt = row.required("created_at", Instant::class.java),
        lastSignInAt = row.required("last_sign_in_at", Instant::class.java)
    )

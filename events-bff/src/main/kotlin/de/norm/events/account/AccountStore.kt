package de.norm.events.account

import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitSingle
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

    /** Creates the account at its first sign-in, or records the sign-in and the latest locale. */
    suspend fun signIn(
        email: String,
        locale: String,
        now: Instant
    ): Account =
        db
            .sql(
                "INSERT INTO accounts.account (email, locale, created_at, last_sign_in_at) VALUES (:email, :locale, :now, :now) " +
                    "ON CONFLICT ((lower(email))) DO UPDATE SET locale = EXCLUDED.locale, last_sign_in_at = EXCLUDED.last_sign_in_at " +
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
}

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

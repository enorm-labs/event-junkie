package de.norm.events.account

import java.time.Instant
import java.util.UUID

/** A row of `accounts.account`. [locale] is the language of every mail to it, `de` or `en`. */
data class Account(
    val id: UUID,
    val email: String,
    val locale: String,
    val createdAt: Instant,
    val lastSignInAt: Instant
) {
    /** The address stays out of `toString`, so an account logged by accident does not carry it. */
    override fun toString(): String = "Account(id=$id)"
}

/** A row of `accounts.session`, as the export shows it: never the token. */
data class SessionInfo(
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant
)

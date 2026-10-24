package de.norm.events.account

import java.time.Instant

data class LinkRequest(
    val email: String,
    val locale: String
)

data class SignInRequest(
    val token: String
)

data class AccountResponse(
    val email: String,
    val locale: String,
    val createdAt: Instant
) {
    companion object {
        fun of(account: Account) = AccountResponse(account.email, account.locale, account.createdAt)
    }
}

/** Who the browser is signed in as, or null. Always a 200, so the SPA's check is not a console error. */
data class SessionResponse(
    val account: AccountResponse?
)

/** Everything stored about the account (GDPR Art. 15 and 20). Grows with follows (#3889) and RSVPs (#3892). */
data class AccountExport(
    val exportedAt: Instant,
    val email: String,
    val locale: String,
    val createdAt: Instant,
    val lastSignInAt: Instant,
    val sessions: List<SessionInfo>
)

package de.norm.events.account

import java.time.Instant

data class LinkRequest(
    val email: String,
    val locale: String,
    /** The page to return to after the sign-in, such as `/de/venues/sisyphos`. Anything else is ignored. */
    val next: String? = null
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

/** Everything stored about the account (GDPR Art. 15 and 20). Grows with RSVPs (#3892). */
data class AccountExport(
    val exportedAt: Instant,
    val email: String,
    val locale: String,
    val createdAt: Instant,
    val lastSignInAt: Instant,
    val sessions: List<SessionInfo>,
    val follows: List<FollowResponse>
)

data class FollowState(
    val following: Boolean
)

/** One thing the account follows. [kind] is `artist`, `venue` or `promoter`; [notify] is `off` until mail exists (#3891). */
data class FollowResponse(
    val kind: String,
    val slug: String,
    val name: String,
    val notify: String,
    val followedAt: Instant
) {
    companion object {
        fun of(follow: FollowedEntity) = FollowResponse(follow.kind.tag, follow.slug, follow.name, follow.notify, follow.followedAt)
    }
}

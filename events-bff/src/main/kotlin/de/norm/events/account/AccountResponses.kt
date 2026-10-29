package de.norm.events.account

import java.time.Instant
import java.util.UUID

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
    val createdAt: Instant,
    /** "Changes to my plans" (#4045): a push when an event marked as interested or going changes. */
    val changePush: Boolean,
    /** "Reminders on the day" (#4253): a push on the day of an event marked as interested or going. */
    val reminderPush: Boolean
) {
    companion object {
        fun of(account: Account) = AccountResponse(account.email, account.locale, account.createdAt, account.changePush, account.reminderPush)
    }
}

/** Who the browser is signed in as, or null. Always a 200, so the SPA's check is not a console error. */
data class SessionResponse(
    val account: AccountResponse?,
    /** False while sign-up is paused (#3906): the sign-in page then says that only existing accounts get a link. */
    val signUpOpen: Boolean
)

/** Everything stored about the account (GDPR Art. 15 and 20). */
data class AccountExport(
    val exportedAt: Instant,
    val email: String,
    val locale: String,
    val createdAt: Instant,
    val lastSignInAt: Instant,
    val changePush: Boolean,
    val reminderPush: Boolean,
    val sessions: List<SessionInfo>,
    val follows: List<FollowResponse>,
    val searches: List<SavedSearchResponse>,
    val rsvps: List<RsvpResponse>,
    /** What the header's bell listed (#4078), read or not, for 30 days. */
    val notifications: List<NotificationResponse>,
    /** One per browser with push notifications on (#3894): the push service's host, never the endpoint. */
    val pushSubscriptions: List<PushSubscriptionInfo>,
    /** The passkeys (#3888): name and dates, never the key. */
    val passkeys: List<PasskeyInfo>
)

data class NotifyRequest(
    val notify: String
)

/** How many follows the bulk bell changed. */
data class NotifyCount(
    val changed: Long
)

/** [notify] is the bell, `off` or `instant` (#4077), and null when not [following]. */
data class FollowState(
    val following: Boolean,
    val notify: String?
)

/** One thing the account follows. [kind] is `artist`, `venue` or `promoter`; [notify] is `off` or `instant`, the bell (#3894). */
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

data class SavedSearchRequest(
    val name: String,
    /** The events list's query string, such as `genre=techno&district=neukoelln&when=weekend`. */
    val query: String
)

data class SavedSearchRename(
    val name: String
)

/** A saved search (#3890). [query] is normalised: keys and values sorted. */
data class SavedSearchResponse(
    val id: UUID,
    val name: String,
    val query: String,
    val savedAt: Instant
) {
    companion object {
        fun of(search: SavedSearch) = SavedSearchResponse(search.id, search.name, search.query, search.savedAt)
    }
}

package de.norm.events.account

import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Everything stored about an account, for the export (GDPR Art. 15 and 20). A feature that stores something per account
 * adds it here, so the export cannot miss it.
 */
@Component
@Suppress("LongParameterList") // One store per kind of account data, so the export cannot miss one, and the clock.
class AccountExporter(
    private val sessionRows: SessionStore,
    private val follows: FollowStore,
    private val searches: SavedSearchStore,
    private val rsvps: RsvpStore,
    private val notifications: NotificationStore,
    private val pushes: PushSubscriptionStore,
    private val passkeys: PasskeyStore,
    private val clock: Clock
) {
    suspend fun export(account: Account) =
        AccountExport(
            exportedAt = clock.instant(),
            email = account.email,
            locale = account.locale,
            createdAt = account.createdAt,
            lastSignInAt = account.lastSignInAt,
            sessions = sessionRows.sessions(account.id),
            follows = follows.list(account.id).map(FollowResponse::of),
            searches = searches.list(account.id).map(SavedSearchResponse::of),
            rsvps = rsvps.list(account.id).map(RsvpResponse::of),
            notifications = notifications.list(account.id, Int.MAX_VALUE).map(NotificationResponse::of),
            pushSubscriptions = pushes.list(account.id),
            passkeys = passkeys.list(account.id)
        )
}

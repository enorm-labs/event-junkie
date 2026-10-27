package de.norm.events.account

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * What the sign-in endpoints did, and how many accounts, sessions and follows exist. Every series is registered at
 * construction, so a rule can read a series that has never moved (`deploy/alerts/README.md`). The gauges read
 * holders that [AccountGaugeRefresh] sets, because a gauge supplier runs on the scrape thread and must not query.
 */
@Component
class AccountMetrics(
    private val registry: MeterRegistry
) {
    private val links = LinkOutcome.entries.associateWith { registry.counter(LINK, TAG_OUTCOME, it.tag) }
    private val signIns = SignInOutcome.entries.associateWith { registry.counter(SIGN_IN, TAG_OUTCOME, it.tag) }
    private val searches = SavedSearchAction.entries.associateWith { registry.counter(SAVED_SEARCH, TAG_ACTION, it.tag) }
    private val rsvps = RsvpAction.entries.associateWith { registry.counter(RSVP, TAG_ACTION, it.tag) }
    private val pushes = PushAction.entries.associateWith { registry.counter(PUSH, TAG_ACTION, it.tag) }
    private val passkeys = PasskeyOutcome.entries.associateWith { registry.counter(PASSKEY, TAG_OUTCOME, it.tag) }
    private val cleanup = CleanupOutcome.entries.associateWith { registry.counter(CLEANUP, TAG_OUTCOME, it.tag) }
    private val follows =
        FollowAction.entries.associateWith { action ->
            FollowKind.entries.associateWith { kind -> registry.counter(FOLLOW, TAG_ACTION, action.tag, TAG_KIND, kind.tag) }
        }

    private val accounts = AccountState.entries.associateWith { gauge(ACCOUNTS, Tags.of(TAG_STATE, it.tag)) }
    private val sessions = gauge(SESSIONS, Tags.empty())
    private val followed = FollowKind.entries.associateWith { gauge(FOLLOWS, Tags.of(TAG_KIND, it.tag)) }

    private fun gauge(
        name: String,
        tags: Tags
    ): AtomicLong = registry.gauge(name, tags, AtomicLong(0))

    fun publish(
        counts: AccountCounts,
        follows: Map<FollowKind, Long>
    ) {
        accounts.getValue(AccountState.ALL).set(counts.total)
        accounts.getValue(AccountState.ACTIVE).set(counts.active)
        sessions.set(counts.sessions)
        followed.forEach { (kind, holder) -> holder.set(follows[kind] ?: 0) }
    }

    fun record(outcome: LinkOutcome) = links.getValue(outcome).increment()

    fun record(outcome: SignInOutcome) = signIns.getValue(outcome).increment()

    fun record(action: SavedSearchAction) = searches.getValue(action).increment()

    fun record(action: RsvpAction) = rsvps.getValue(action).increment()

    fun record(action: PushAction) = pushes.getValue(action).increment()

    fun record(outcome: PasskeyOutcome) = passkeys.getValue(outcome).increment()

    fun record(
        action: FollowAction,
        kind: FollowKind
    ) = follows.getValue(action).getValue(kind).increment()

    fun record(
        outcome: CleanupOutcome,
        count: Long = 1
    ) = cleanup.getValue(outcome).increment(count.toDouble())

    companion object {
        /** `bff.auth.link{outcome}`: requests for a sign-in link. */
        const val LINK = "bff.auth.link"

        /** `bff.auth.sign_in{outcome}`: links used. */
        const val SIGN_IN = "bff.auth.sign_in"

        /** `bff.accounts.cleanup{outcome}`: what the daily cleanup of inactive accounts, links and sessions did (#3905). */
        const val CLEANUP = "bff.accounts.cleanup"

        /** `bff.account.saved_search{action}`: saved searches saved, renamed and deleted (#3890). */
        const val SAVED_SEARCH = "bff.account.saved_search"

        /** `bff.account.rsvp{action}`: RSVPs set or changed, and withdrawn (#3892). */
        const val RSVP = "bff.account.rsvp"

        /** `bff.account.push{action}`: browsers subscribed and unsubscribed, and bells turned on and off (#3894). What was sent is `bff.push.sent`. */
        const val PUSH = "bff.account.push"

        /** `bff.account.passkey{outcome}`: passkeys registered, used to sign in, renamed and deleted, and the refusals (#3888). */
        const val PASSKEY = "bff.account.passkey"

        /** `bff.account.follow{action,kind}`: follows and unfollows (#3889). */
        const val FOLLOW = "bff.account.follow"

        /** `bff_accounts_count{state}`: all accounts, and those used in the last 30 days. The same on every replica. */
        const val ACCOUNTS = "bff.accounts.count"

        /** `bff_accounts_sessions`: live sessions, one per signed-in browser. */
        const val SESSIONS = "bff.accounts.sessions"

        /** `bff_accounts_follows{kind}`: follows that exist, per artist / venue / promoter. */
        const val FOLLOWS = "bff.accounts.follows"

        const val TAG_OUTCOME = "outcome"
        const val TAG_ACTION = "action"
        const val TAG_KIND = "kind"
        const val TAG_STATE = "state"
    }
}

/** The two account counts `bff.accounts.count` publishes. Active is a sign-in or a renewed session in the last 30 days. */
enum class AccountState(
    val tag: String
) {
    ALL("all"),
    ACTIVE("active")
}

/** How a request for a sign-in link ended. Only [IP_THROTTLED] and the two mail failures are visible to the caller. */
enum class LinkOutcome(
    val tag: String
) {
    /** A link went out. */
    SENT("sent"),

    /** The address asked for too many links this hour. Answered as if sent, so the answer says nothing about the address. */
    ADDRESS_THROTTLED("address_throttled"),

    /** Sign-up is paused and the address has no account (#3906). Answered as if sent, so the answer says nothing about the address. */
    SIGN_UP_PAUSED("sign_up_paused"),

    /** The caller's address asked for too many links this hour. A 429. */
    IP_THROTTLED("ip_throttled"),

    /** No mail sender on this cluster (ADR-054): `mail.existingSecret` is not set. A 503. */
    MAIL_UNAVAILABLE("mail_unavailable"),

    /** The relay refused or did not answer. A 503. */
    MAIL_FAILED("mail_failed")
}

/** How using a sign-in link ended. */
enum class SignInOutcome(
    val tag: String
) {
    OK("ok"),

    /** Unknown, used or expired. One outcome, because the answer to the caller is one answer. */
    INVALID("invalid")
}

/** What the daily cleanup did, counted per row. [FAILED] counts the steps that threw. */
enum class CleanupOutcome(
    val tag: String
) {
    LINKS_PURGED("links_purged"),
    SESSIONS_PURGED("sessions_purged"),

    /** Follows of an artist, venue or promoter that was deleted or merged away (#3889). */
    FOLLOWS_PURGED("follows_purged"),

    /** RSVPs whose event the importer removed (#3892). */
    RSVPS_PURGED("rsvps_purged"),

    /** Passkey challenges nobody answered (#3888). */
    CHALLENGES_PURGED("challenges_purged"),

    /** A warning mail went out. */
    WARNED("warned"),

    /** The relay refused a warning. The claim is released, so the next run tries again. */
    WARN_FAILED("warn_failed"),
    DELETED("deleted"),
    FAILED("failed")
}

enum class FollowAction(
    val tag: String
) {
    FOLLOW("follow"),
    UNFOLLOW("unfollow")
}

enum class SavedSearchAction(
    val tag: String
) {
    SAVE("save"),
    RENAME("rename"),
    DELETE("delete")
}

enum class RsvpAction(
    val tag: String
) {
    SET("set"),
    WITHDRAW("withdraw")
}

enum class PushAction(
    val tag: String
) {
    SUBSCRIBE("subscribe"),
    UNSUBSCRIBE("unsubscribe"),
    BELL_ON("bell_on"),
    BELL_OFF("bell_off")
}

/** How a passkey request ended (#3888). */
enum class PasskeyOutcome(
    val tag: String
) {
    REGISTERED("registered"),

    /** A bad answer, a wrong origin, or a used or expired challenge. */
    REGISTER_REFUSED("register_refused"),

    /** The account has the most passkeys it may have. */
    LIMIT("limit"),
    SIGNED_IN("signed_in"),

    /** One outcome for every cause, because the answer to the caller is one answer. */
    SIGN_IN_REFUSED("sign_in_refused"),

    /** The client asked for too many sign-in challenges this hour. A 429. */
    THROTTLED("throttled"),
    RENAMED("renamed"),
    DELETED("deleted")
}

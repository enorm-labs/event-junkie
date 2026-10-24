package de.norm.events.account

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * What the sign-in endpoints did. Every outcome is registered at construction, so a rule can read a
 * series that has never moved (`deploy/alerts/README.md`).
 */
@Component
class AccountMetrics(
    registry: MeterRegistry
) {
    private val links = LinkOutcome.entries.associateWith { registry.counter(LINK, TAG_OUTCOME, it.tag) }
    private val signIns = SignInOutcome.entries.associateWith { registry.counter(SIGN_IN, TAG_OUTCOME, it.tag) }
    private val cleanup = CleanupOutcome.entries.associateWith { registry.counter(CLEANUP, TAG_OUTCOME, it.tag) }

    fun record(outcome: LinkOutcome) = links.getValue(outcome).increment()

    fun record(outcome: SignInOutcome) = signIns.getValue(outcome).increment()

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

        const val TAG_OUTCOME = "outcome"
    }
}

/** How a request for a sign-in link ended. Only [IP_THROTTLED] and the two mail failures are visible to the caller. */
enum class LinkOutcome(
    val tag: String
) {
    /** A link went out. */
    SENT("sent"),

    /** The address asked for too many links this hour. Answered as if sent, so the answer says nothing about the address. */
    ADDRESS_THROTTLED("address_throttled"),

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

    /** A warning mail went out. */
    WARNED("warned"),

    /** The relay refused a warning. The claim is released, so the next run tries again. */
    WARN_FAILED("warn_failed"),
    DELETED("deleted"),
    FAILED("failed")
}

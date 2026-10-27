package de.norm.events.account

import de.norm.events.common.BERLIN
import de.norm.events.mail.Mailer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The deletion route for inactive accounts the privacy notice promises (#3905, LEGAL.md §7.1). Daily:
 * expired links and sessions go, and so do follows of an artist, venue or promoter that no longer
 * exists (#3889). An account unused for 23 months gets a warning mail, and one unused for 24 months,
 * warned at least 30 days earlier, is deleted. Use is a sign-in or a renewed session.
 *
 * Every replica runs it. The purges and the deletion are idempotent, and [AccountStore.claimWarnings]
 * hands each account to one replica, so a warning goes out once.
 */
@Component
@Suppress("LongParameterList") // One job over five stores, the mailer, its metrics, the clock and the origin its mail links to.
class InactiveAccountCleanup(
    private val accounts: AccountStore,
    private val sessions: SessionStore,
    private val follows: FollowStore,
    private val rsvps: RsvpStore,
    private val passkeys: PasskeyStore,
    private val mailer: ObjectProvider<Mailer>,
    private val metrics: AccountMetrics,
    private val clock: Clock,
    @Value("\${app.site.origin}") private val origin: String
) {
    @Scheduled(cron = "\${app.accounts.cleanup-cron:0 30 4 * * *}", zone = "Europe/Berlin")
    suspend fun run() {
        val now = clock.instant()
        val berlin = now.atZone(BERLIN)
        logger.info { "Account cleanup started" }
        // The per-address rate limit counts the links of the last hour, so a link stays an hour past its expiry.
        step(CleanupOutcome.LINKS_PURGED) { accounts.deleteLinks(now.minus(LINK_KEPT_AFTER_EXPIRY)) }
        step(CleanupOutcome.SESSIONS_PURGED) { sessions.deleteExpired(now) }
        step(CleanupOutcome.FOLLOWS_PURGED) { follows.deleteOrphans() }
        step(CleanupOutcome.RSVPS_PURGED) { rsvps.deleteOrphans() }
        step(CleanupOutcome.CHALLENGES_PURGED) { passkeys.deleteExpiredChallenges(now) }
        val sender = mailer.ifAvailable
        if (sender == null) {
            logger.warn { "Account cleanup: no mail sender on this cluster, so no deletion warnings" }
        } else {
            step(CleanupOutcome.WARNED) { warn(sender, berlin.minusMonths(WARN_AFTER_MONTHS).toInstant(), now) }
        }
        step(CleanupOutcome.DELETED) {
            accounts.deleteInactive(berlin.minusMonths(DELETE_AFTER_MONTHS).toInstant(), now.minus(WARNING_NOTICE))
        }
        logger.info { "Account cleanup finished" }
    }

    /** Sends the warnings this replica claimed. Answers how many went out. */
    private suspend fun warn(
        sender: Mailer,
        inactiveSince: Instant,
        now: Instant
    ): Long {
        val claimed = accounts.claimWarnings(inactiveSince, now, WARNINGS_PER_RUN)
        var failed = 0L
        for (account in claimed) {
            val deletedFrom = maxOf(account.lastSignInAt.atZone(BERLIN).plusMonths(DELETE_AFTER_MONTHS), now.plus(WARNING_NOTICE).atZone(BERLIN))
            val message = DeletionWarningMail.message(account, origin, account.lastSignInAt.atZone(BERLIN).toLocalDate(), deletedFrom.toLocalDate())
            // MailException only, as in SignInLinks: Mailer has logged it, and a cancelled job must stay cancelled.
            try {
                sender.send(message).awaitSingleOrNull()
            } catch (_: MailException) {
                accounts.releaseWarning(account.id)
                failed++
            }
        }
        if (failed > 0) metrics.record(CleanupOutcome.WARN_FAILED, failed)
        return claimed.size - failed
    }

    /** Runs one step, counts its rows under [outcome] and logs them. A failure is logged and counted, and the next step still runs. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun step(
        outcome: CleanupOutcome,
        block: suspend () -> Long
    ) {
        try {
            val count = block()
            metrics.record(outcome, count)
            logger.info { "Account cleanup: ${outcome.tag} $count" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            metrics.record(CleanupOutcome.FAILED)
            logger.warn(e) { "Account cleanup: the ${outcome.tag} step failed" }
        }
    }

    private companion object {
        const val WARN_AFTER_MONTHS = 23L
        const val DELETE_AFTER_MONTHS = 24L
        val WARNING_NOTICE: Duration = Duration.ofDays(30)
        val LINK_KEPT_AFTER_EXPIRY: Duration = Duration.ofHours(1)

        /** About a day's share of Scaleway's monthly quota (ADR-054). The rest wait for the next run. */
        const val WARNINGS_PER_RUN = 300
    }
}

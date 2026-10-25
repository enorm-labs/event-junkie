package de.norm.events.account

import com.github.benmanes.caffeine.cache.Caffeine
import de.norm.events.mail.Mailer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Issues and uses sign-in links (ADR-053). Its own endpoints rather than Spring Security's
 * `oneTimeTokenLogin()`: that DSL hands its token service only an address and an expiry, and a
 * rejected request falls through to a 404. This class keeps the locale with the token and answers.
 *
 * Two rate limits. Per address, counted in `one_time_token`, answered as if the link went out, so the
 * answer never says whether an address has an account. Per client address, in this pod's memory for
 * an hour, answered with a 429.
 */
@Component
class SignInLinks(
    private val store: AccountStore,
    private val mailer: ObjectProvider<Mailer>,
    private val metrics: AccountMetrics,
    private val clock: Clock,
    @Value("\${app.site.origin}") private val origin: String
) {
    private val requestsPerClient =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(MAX_TRACKED_CLIENTS)
            .build<String, AtomicInteger>()

    suspend fun request(
        email: String,
        locale: String,
        client: String,
        next: String? = null
    ): LinkOutcome =
        decide(email, locale, client, next).also { outcome ->
            metrics.record(outcome)
            logger.info { "Sign-in link request: ${outcome.tag}" }
        }

    private suspend fun decide(
        email: String,
        locale: String,
        client: String,
        next: String?
    ): LinkOutcome {
        if (requestsPerClient.get(client) { AtomicInteger() }.incrementAndGet() > LINKS_PER_CLIENT_PER_HOUR) return LinkOutcome.IP_THROTTLED
        val sender = mailer.ifAvailable ?: return LinkOutcome.MAIL_UNAVAILABLE
        val now = clock.instant()
        if (store.linksSince(email, now.minus(Duration.ofHours(1))) >= LINKS_PER_ADDRESS_PER_HOUR) return LinkOutcome.ADDRESS_THROTTLED

        val token = SecretTokens.next()
        store.issueToken(SecretTokens.hash(token), email, locale, now, now.plus(LINK_VALIDITY))
        // MailException only: a cancelled request must stay cancelled, not become a "mail failed".
        return try {
            sender.send(SignInMail.message(email, locale, origin, SignInMail.link(origin, locale, token, next), LINK_VALIDITY.toMinutes())).awaitSingleOrNull()
            LinkOutcome.SENT
        } catch (_: MailException) {
            LinkOutcome.MAIL_FAILED
        }
    }

    /** The account the token signs in, created at its first use, or null for an unknown, used or expired token. */
    suspend fun use(token: String): Account? {
        val now = clock.instant()
        val account = store.consumeToken(SecretTokens.hash(token), now)?.let { (email, locale) -> store.signIn(email, locale, now) }
        val outcome = if (account == null) SignInOutcome.INVALID else SignInOutcome.OK
        metrics.record(outcome)
        logger.info { "Sign-in link used: ${outcome.tag}" }
        return account
    }

    private companion object {
        val LINK_VALIDITY: Duration = Duration.ofMinutes(15)
        const val LINKS_PER_ADDRESS_PER_HOUR = 3
        const val LINKS_PER_CLIENT_PER_HOUR = 20
        const val MAX_TRACKED_CLIENTS = 100_000L
    }
}

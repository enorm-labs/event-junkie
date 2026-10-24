package de.norm.events.account

import kotlinx.coroutines.reactor.mono
import org.springframework.http.ResponseCookie
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.server.context.ServerSecurityContextRepository
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration

/**
 * The session behind the `__Host-ej_session` cookie, in `accounts.session` (ADR-053). PostgreSQL and
 * not the WebSession, because the BFF runs two replicas. The cookie holds a random token, the table
 * its SHA-256.
 *
 * A session lasts 30 days from its last use. The row and the cookie are renewed at most once a day,
 * so a busy visitor does not cost a write per request.
 */
@Component
class SessionSecurityContextRepository(
    private val store: SessionStore,
    private val clock: Clock
) : ServerSecurityContextRepository {
    @Suppress("ForbiddenVoid") // Mono<Void> is the interface's own return type.
    override fun save(
        exchange: ServerWebExchange,
        context: SecurityContext?
    ): Mono<Void> =
        mono {
            val account = (context?.authentication as? AccountAuthentication)?.account
            if (account == null) {
                cookieToken(exchange)?.let { store.deleteSession(SecretTokens.hash(it)) }
                clearCookies(exchange)
            } else {
                val token = SecretTokens.next()
                val now = clock.instant()
                store.createSession(SecretTokens.hash(token), account.id, now, now.plus(LIFETIME))
                setCookie(exchange, token)
            }
        }.then()

    override fun load(exchange: ServerWebExchange): Mono<SecurityContext> =
        mono {
            val token = cookieToken(exchange) ?: return@mono null
            val hash = SecretTokens.hash(token)
            val now = clock.instant()
            val (account, lastSeen) = store.findSession(hash, now) ?: return@mono null
            if (Duration.between(lastSeen, now) > RENEW_AFTER) {
                store.extendSession(hash, now, now.plus(LIFETIME))
                setCookie(exchange, token)
            }
            SecurityContextImpl(AccountAuthentication(account))
        }

    /** Ends every session of the account the request carries, and clears this browser's cookies. */
    suspend fun endAll(
        exchange: ServerWebExchange,
        account: Account
    ) {
        store.deleteSessions(account.id)
        clearCookies(exchange)
    }

    private fun cookieToken(exchange: ServerWebExchange) =
        exchange.request.cookies
            .getFirst(COOKIE)
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun setCookie(
        exchange: ServerWebExchange,
        token: String
    ) = exchange.response.addCookie(cookie(token, LIFETIME))

    /** Both cookies go on sign-out: nothing of the account stays on the device (§ 25 TDDDG). */
    private fun clearCookies(exchange: ServerWebExchange) {
        exchange.response.addCookie(cookie("", Duration.ZERO))
        exchange.response.addCookie(
            ResponseCookie
                .from(CSRF_COOKIE, "")
                .path("/")
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
                .build()
        )
    }

    private fun cookie(
        value: String,
        maxAge: Duration
    ) = ResponseCookie
        .from(COOKIE, value)
        .path("/")
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .maxAge(maxAge)
        .build()

    companion object {
        /** `__Host-`: the browser refuses it unless it is Secure, host-only and on `/`. */
        const val COOKIE = "__Host-ej_session"
        const val CSRF_COOKIE = "XSRF-TOKEN"
        val LIFETIME: Duration = Duration.ofDays(30)
        private val RENEW_AFTER: Duration = Duration.ofDays(1)
    }
}

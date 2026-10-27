package de.norm.events.account

import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository
import org.springframework.web.server.ServerWebExchange
import java.time.Duration

/**
 * The `XSRF-TOKEN` cookie: readable by the SPA, as long-lived as the session, and gone on a delete.
 *
 * Spring runs the cookie customizer after it sets a delete's zero max-age, so a customizer that sets the lifetime also
 * keeps an emptied cookie for 30 days (#4055). A delete therefore goes through a repository that sets no lifetime.
 */
internal fun csrfTokenRepository(): ServerCsrfTokenRepository {
    val saving = csrfCookieRepository(SessionSecurityContextRepository.LIFETIME)
    val deleting = csrfCookieRepository(maxAge = null)
    return object : ServerCsrfTokenRepository by saving {
        override fun saveToken(
            exchange: ServerWebExchange,
            token: CsrfToken?
        ) = (if (token == null) deleting else saving).saveToken(exchange, token)
    }
}

private fun csrfCookieRepository(maxAge: Duration?) =
    CookieServerCsrfTokenRepository.withHttpOnlyFalse().apply {
        setCookieCustomizer { cookie ->
            cookie.path("/").secure(true).sameSite("Strict")
            maxAge?.let(cookie::maxAge)
        }
    }

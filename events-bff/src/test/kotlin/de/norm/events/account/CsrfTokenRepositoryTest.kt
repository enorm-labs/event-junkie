package de.norm.events.account

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.web.server.csrf.CsrfToken

/** A saved token lives as long as the session; a delete removes the cookie instead of keeping it empty for 30 days (#4055). */
class CsrfTokenRepositoryTest {
    private val repository = csrfTokenRepository()

    @Test
    fun `a saved token carries the session lifetime`() {
        val token = repository.generateToken(exchange()).block()

        setCookie(token) shouldBe "XSRF-TOKEN=${token!!.token}; Path=/; Max-Age=2592000; Expires=*; Secure; SameSite=Strict"
    }

    @Test
    fun `a delete expires the cookie at once`() {
        setCookie(null) shouldBe "XSRF-TOKEN=; Path=/; Max-Age=0; Expires=*; Secure; SameSite=Strict"
    }

    private fun setCookie(token: CsrfToken?): String {
        val exchange = exchange()
        repository.saveToken(exchange, token).block()
        exchange.response.setComplete().block()
        val headers = exchange.response.headers[HttpHeaders.SET_COOKIE].orEmpty()
        headers shouldHaveSize 1
        // Expires is computed from the clock, and Max-Age already says the same thing.
        return headers.single().replace(Regex("Expires=[^;]+"), "Expires=*")
    }

    private fun exchange() = MockServerWebExchange.from(MockServerHttpRequest.get("https://localhost/api/auth/csrf"))
}

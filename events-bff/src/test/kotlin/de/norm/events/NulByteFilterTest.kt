package de.norm.events

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

/**
 * The filter decides on the decoded request, so `%00` in the path, in a parameter value and in a
 * parameter name are three ways in (#1441). The chain must not run for any of them, because the
 * whole point is that no handler and no repository sees the byte.
 */
class NulByteFilterTest {
    private var chainCalled = false

    private fun run(exchange: MockServerWebExchange): MockServerWebExchange {
        chainCalled = false
        NulByteFilter()
            .filter(exchange) {
                chainCalled = true
                Mono.empty()
            }.block()
        return exchange
    }

    private fun get(
        template: String,
        vararg vars: Any
    ) = MockServerWebExchange.from(MockServerHttpRequest.get(template, *vars))

    @Test
    fun `a NUL byte in a query value is a 400 problem and never reaches the chain`() {
        val exchange = run(get("/api/artists?q={q}", "\u0000"))

        chainCalled shouldBe false
        exchange.response.statusCode shouldBe HttpStatus.BAD_REQUEST
        exchange.response.headers.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON
        exchange.response.bodyAsString
            .block()!!
            .contains("\"title\":\"NUL byte in request\"") shouldBe true
    }

    @Test
    fun `a NUL byte in a query name is rejected too`() {
        val exchange = run(get("/api/artists?{name}=x", "q\u0000"))

        chainCalled shouldBe false
        exchange.response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `a NUL byte in the path is rejected`() {
        val exchange = run(get("/api/venues/{slug}", "kes\u0000selhaus"))

        chainCalled shouldBe false
        exchange.response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/artists?q=Møbius%20Trio", "/api/venues/jazzkeller-kreuzberg", "/api/events?q=%25&sort=eventDate,ASC"])
    fun `everything else passes through untouched`(uri: String) {
        val exchange = run(MockServerWebExchange.from(MockServerHttpRequest.get(uri)))

        chainCalled shouldBe true
        exchange.response.statusCode shouldBe null
    }
}

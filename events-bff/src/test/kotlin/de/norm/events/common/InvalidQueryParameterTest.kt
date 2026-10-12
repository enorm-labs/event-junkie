package de.norm.events.common

import de.norm.events.BaseControllerTest
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.MediaType

/**
 * A query parameter whose value does not convert is a `400` that names the parameter, and only
 * that (#3145). Spring's own message named the controller method and its DTO classes. The words
 * `mismatch`, `exception` and `error` stay out too: ZAP rule 90022 reads them as a disclosure.
 */
class InvalidQueryParameterTest : BaseControllerTest() {
    @ParameterizedTest
    @CsvSource(
        "/events?from=notadate, from, notadate, a date as YYYY-MM-DD",
        "/events?maxPrice=abc, maxPrice, abc, a number",
        "/events?free=maybe, free, maybe, true or false",
        "/events/calendar?from=2026-06-01&to=soon, to, soon, a date as YYYY-MM-DD",
        "/search?q=techno&limit=many, limit, many, a whole number"
    )
    fun `a value that does not convert names the parameter and nothing internal`(
        uri: String,
        parameter: String,
        value: String,
        expected: String
    ) {
        val body =
            webTestClient
                .get()
                .uri(uri)
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("'$parameter' must be $expected, not '$value'.")
                .jsonPath("$.invalid[0]")
                .isEqualTo(parameter)
                .jsonPath("$.requestId")
                .isNotEmpty
                .returnResult()
                .responseBody!!
                .decodeToString()

        for (internal in listOf("EventController", "SearchController", "de.norm.events", "java.", "springdoc", "swagger", "Continuation")) {
            body shouldNotContain internal
        }
        for (word in listOf("mismatch", "exception", "error")) {
            body.lowercase() shouldNotContain word
        }
        body shouldContain parameter
    }

    @Test
    fun `every parameter of one filter object that does not convert is named`() {
        webTestClient
            .get()
            .uri("/events?minPrice=x&maxPrice=y")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.invalid.length()")
            .isEqualTo(2)
    }

    @Test
    fun `a long value is cut short`() {
        webTestClient
            .get()
            .uri("/events?from=0123456789012345678901234567890123456789-and-then-some")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("'from' must be a date as YYYY-MM-DD, not '0123456789012345678901234567890123456789…'.")
    }
}

package de.norm.events

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.net.URI

/**
 * Every error that reaches Boot's handler is a problem, whatever the client accepts (#1446). The
 * routes below are the ones the API scan hit: a browser-style `Accept` on a route that does not
 * exist and on a parameter that does not bind. Both used to answer with the Whitelabel page.
 *
 * The 5xx branch is asserted on the pure builder: since #1448 nothing from outside provokes a 500,
 * and a test that could would be a defect to fix rather than a fixture to keep.
 */
class ProblemDetailErrorHandlerTest : BaseControllerTest() {
    @Test
    fun `an unknown route answered to a browser is a 404 problem, not a Whitelabel page`() {
        webTestClient
            .get()
            .uri("/nothing-here")
            .accept(MediaType.TEXT_HTML)
            .exchange()
            .expectStatus()
            .isNotFound
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(404)
            .jsonPath("$.title")
            .isEqualTo("Not Found")
            .jsonPath("$.instance")
            .isEqualTo("/api/nothing-here")
            .jsonPath("$.requestId")
            .exists()
    }

    @Test
    fun `a parameter that does not bind answered to a browser is a 400 problem`() {
        val body =
            webTestClient
                .get()
                .uri("/events?from=from")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        body shouldNotContain "<html"
        body shouldNotContain "Whitelabel"
    }

    @Test
    fun `an unknown route answered to any client is the same problem`() {
        webTestClient
            .get()
            .uri("/nothing-here")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(404)
    }

    @Test
    fun `a 5xx carries a fixed detail and never the exception message`() {
        val problem = ProblemDetailErrorHandler.problemFor(500, "invalid byte sequence for encoding UTF8", "/api/artists", "abc-123")

        problem.status shouldBe 500
        problem.title shouldBe "Internal Server Error"
        problem.detail shouldBe ProblemDetailErrorHandler.SERVER_ERROR_DETAIL
        problem.instance shouldBe URI.create("/api/artists")
        problem.properties?.get("requestId") shouldBe "abc-123"
    }

    @Test
    fun `a 4xx carries the message, and a blank one is left out`() {
        ProblemDetailErrorHandler.problemFor(400, "Type mismatch.", "/api/events", null).detail shouldBe "Type mismatch."
        ProblemDetailErrorHandler.problemFor(404, "", "/api/x", null).detail.shouldBeNull()
    }
}

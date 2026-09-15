package de.norm.events.common

import de.norm.events.BaseControllerTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.web.util.UriBuilder
import java.net.URI

/**
 * Pins that a NUL byte is refused at the edge on the routes where it used to reach PostgreSQL
 * (#1441). The unit test on the filter proves the decision; this proves the filter is registered
 * and sits before the repositories, which a unit test cannot.
 *
 * Built through the [UriBuilder] rather than as a string: `WebTestClient.uri(String)` is a template
 * and encodes `%00` to `%2500`, which is the two-character text `%00` on the wire and a legitimate
 * search term.
 */
class NulByteRequestTest : BaseControllerTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    fun `a NUL byte anywhere in the request is a 400 problem, never a 500`(
        @Suppress("UNUSED_PARAMETER") name: String,
        request: (UriBuilder) -> URI
    ) {
        webTestClient
            .get()
            .uri(request)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.title")
            .isEqualTo("NUL byte in request")
    }

    companion object {
        private const val NUL = "\u0000"

        private fun case(
            name: String,
            request: (UriBuilder) -> URI
        ) = arrayOf<Any>(name, request)

        @JvmStatic
        fun requests() =
            listOf(
                case("/artists?q=NUL") { it.path("/artists").queryParam("q", NUL).build() },
                case("/promoters?q=NUL") { it.path("/promoters").queryParam("q", NUL).build() },
                case("/venues?q=NUL") { it.path("/venues").queryParam("q", NUL).build() },
                case("/venues?district=NUL") { it.path("/venues").queryParam("district", NUL).build() },
                case("/events?q=NUL") { it.path("/events").queryParam("q", NUL).build() },
                case("/events?venue=NUL") { it.path("/events").queryParam("venue", NUL).build() },
                case("/events/calendar?q=NUL") {
                    it
                        .path("/events/calendar")
                        .queryParam("from", "2026-06-01")
                        .queryParam("to", "2026-06-30")
                        .queryParam("q", NUL)
                        .build()
                },
                case("/venues/NUL") { it.pathSegment("venues", NUL).build() },
                case("/artists/xNULy") { it.pathSegment("artists", "x${NUL}y").build() }
            )
    }
}

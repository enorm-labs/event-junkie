package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody

/**
 * Pins the rejection of unknown fields in an admin request body (#814).
 *
 * The defect was not a crash. `PATCH` accepted any JSON, dropped what it did not recognise, and
 * returned `200` with the unchanged row — so `scripts/apply-licence-review.py` reported
 * "Wrote 85 of 85" against a database where nothing had been written. The assertions that matter
 * are therefore about what the *response* says, not only about the status code.
 */
class EventSourceUnknownFieldTest : BaseControllerTest() {
    private fun createVenue(): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.astra())
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun createSource(): EventSourceResponse =
        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.cassiopeia(venueId = createVenue().id))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventSourceResponse>()
            .returnResult()
            .responseBody!!

    @Test
    fun `a field that has never existed is rejected, not dropped`() {
        val slug = createSource().slug

        webTestClient
            .patch()
            .uri("/api/admin/event-sources/$slug")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"descriptionLicence":"PROHIBITED","totalNonsenseField":"xyzzy"}""")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { detail ->
                detail shouldContain "totalNonsenseField"
                // The accepted names are listed so a caller can spot a typo without the source.
                detail shouldContain "descriptionLicence"
            }
    }

    @Test
    fun `rejecting the body writes nothing at all`() {
        val slug = createSource().slug

        webTestClient
            .patch()
            .uri("/api/admin/event-sources/$slug")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"descriptionLicence":"PROHIBITED","totalNonsenseField":"xyzzy"}""")
            .exchange()
            .expectStatus()
            .isBadRequest

        // The valid half of a rejected body must not be applied — a partial write would be worse
        // than the silent no-op it replaces.
        webTestClient
            .get()
            .uri("/api/admin/event-sources/$slug")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                result.responseBody!!.descriptionLicence shouldBe null
                result.responseBody!!.licenceReviewedAt shouldBe null
            }
    }

    @Test
    fun `a misspelt licence field is rejected rather than counted as a write`() {
        val slug = createSource().slug

        // `descriptionLicense` — the American spelling, and the plausible typo.
        webTestClient
            .patch()
            .uri("/api/admin/event-sources/$slug")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"descriptionLicense":"PROHIBITED"}""")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { it shouldContain "descriptionLicense" }
    }

    @Test
    fun `the fields the endpoint does accept still apply`() {
        val slug = createSource().slug

        webTestClient
            .patch()
            .uri("/api/admin/event-sources/$slug")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {"descriptionLicence":"PROHIBITED","imageLicence":"UNCLEAR",
                 "licenceSourceUrl":"https://example.com/presse","licenceNote":"Pressefotos honorarfrei",
                 "enabled":false,"importIntervalMinutes":720,"maxRetries":5}
                """.trimIndent()
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                val source = result.responseBody!!
                source.descriptionLicence shouldBe "PROHIBITED"
                source.imageLicence shouldBe "UNCLEAR"
                source.licenceSourceUrl shouldBe "https://example.com/presse"
                source.enabled shouldBe false
                source.importIntervalMinutes shouldBe 720
                source.maxRetries shouldBe 5
                // Stamped server-side, which is what the applier now verifies rather than assuming.
                source.licenceReviewedAt shouldNotBe null
            }
    }

    @Test
    fun `an empty body is still accepted and still changes nothing`() {
        val slug = createSource().slug

        // `{}` carries no unknown field, so it is not a 400 — it is the one remaining way to ask for
        // nothing. It must not stamp a review, and the service must not log it as an update.
        webTestClient
            .patch()
            .uri("/api/admin/event-sources/$slug")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                result.responseBody!!.licenceReviewedAt shouldBe null
                result.responseBody!!.descriptionLicence shouldBe null
            }
    }
}

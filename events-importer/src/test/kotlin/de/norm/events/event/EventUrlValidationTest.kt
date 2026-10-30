package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** The admin API refuses a ticket or Facebook link with no host on POST, PUT and PATCH (#4288). */
class EventUrlValidationTest : BaseControllerTest() {
    private val jsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val hostless = listOf("https://", "http://", "https:///path", "ftp://x")
    private val valid = listOf(null, "https://www.eventim.de/event/the-adicts", "https://tickets.example.de/a b")

    @Test
    fun `POST refuses a link with no host and accepts null and a valid link`() {
        val venue = createVenue("url-post")
        hostless.forEachIndexed { i, url ->
            post(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:url-post-t$i", ticketUrl = url))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("ticketUrl")
                .jsonPath("$.errors[0].message")
                .isEqualTo("Ticket URL must be an http or https URL with a host")
            post(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:url-post-f$i", facebookEventUrl = url))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("facebookEventUrl")
                .jsonPath("$.errors[0].message")
                .isEqualTo("Facebook event URL must be an http or https URL with a host")
        }
        valid.forEachIndexed { i, url ->
            val request = EventRequestFixtures.create(venueId = venue.id, title = "Valid $i", sourceId = "test:url-post-ok$i")
            post(request.copy(ticketUrl = url, facebookEventUrl = url))
                .expectStatus()
                .isCreated
        }
    }

    @Test
    fun `PUT refuses a link with no host and accepts null and a valid link`() {
        val venue = createVenue("url-put")
        val request = EventRequestFixtures.create(venueId = venue.id, sourceId = "test:url-put")
        val created = createEvent(request)
        hostless.forEach { url ->
            put(created.id, request.copy(ticketUrl = url)).expectStatus().isBadRequest
            put(created.id, request.copy(facebookEventUrl = url)).expectStatus().isBadRequest
        }
        valid.forEach { url ->
            put(created.id, request.copy(ticketUrl = url, facebookEventUrl = url)).expectStatus().isOk
        }
    }

    @Test
    fun `PATCH refuses a link with no host and accepts null and a valid link`() {
        val venue = createVenue("url-patch")
        val created = createEvent(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:url-patch"))
        hostless.forEach { url ->
            for (field in listOf("ticketUrl", "facebookEventUrl")) {
                patch(created.id, mapOf(field to url))
                    .expectStatus()
                    .isBadRequest
                    .expectBody<String>()
                    .returnResult()
                    .responseBody!! shouldContain field
            }
        }
        getEvent(created.id).pinnedFields shouldBe emptyList()
        valid.forEach { url ->
            patch(created.id, mapOf("ticketUrl" to url, "facebookEventUrl" to url)).expectStatus().isOk
            getEvent(created.id).ticketUrl shouldBe url
        }
    }

    private fun createVenue(name: String): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.create(name = name))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun createEvent(request: EventRequest): EventResponse =
        post(request)
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private fun post(request: EventRequest) =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request)
            .exchange()

    private fun put(
        id: Long,
        request: EventRequest
    ) = webTestClient
        .put()
        .uri("/api/admin/events/$id")
        .bodyValue(request)
        .exchange()

    private fun patch(
        id: Long,
        fields: Map<String, String?>
    ) = webTestClient
        .patch()
        .uri("/api/admin/events/$id")
        .header("Content-Type", "application/json")
        .bodyValue(jsonMapper.writeValueAsString(fields))
        .exchange()

    private fun getEvent(id: Long): EventResponse =
        webTestClient
            .get()
            .uri("/api/admin/events/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!
}

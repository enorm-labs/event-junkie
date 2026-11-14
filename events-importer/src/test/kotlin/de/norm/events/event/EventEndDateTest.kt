package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate

/** The last day of a run, such as a Christmas market (#4972): set on the POST, kept, changed and cleared through the PUT. */
class EventEndDateTest : BaseControllerTest() {
    private val jsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `POST event stores the last day, and PUT keeps it when left out and clears it with null`() {
        val request = EventRequestFixtures.create(venueId = createVenue("Market Square").id, sourceId = "manual:market-square:run")
        val run = requestJson(request).put("eventDate", "2027-11-23")
        val created = send("POST", "/api/admin/events", run.deepCopy().put("endDate", "2027-12-31"), expected = 201)
        created.endDate shouldBe LocalDate.of(2027, 12, 31)

        // A form that does not know the field, or a script, keeps the end.
        send("PUT", "/api/admin/events/${created.id}", run.deepCopy().put("title", "Edited")).endDate shouldBe LocalDate.of(2027, 12, 31)
        send("PUT", "/api/admin/events/${created.id}", run.deepCopy().put("endDate", "2027-12-23")).endDate shouldBe LocalDate.of(2027, 12, 23)
        send("PUT", "/api/admin/events/${created.id}", run.deepCopy().putNull("endDate")).endDate shouldBe null
    }

    @Test
    fun `POST event refuses a last day before the first`() {
        val request = EventRequestFixtures.create(venueId = createVenue("Backwards Square").id, sourceId = "manual:backwards:run")
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(requestJson(request).put("eventDate", "2027-11-23").put("endDate", "2027-11-22").toString())
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    /** The request as the wire carries it, so a test can tell a missing field from an explicit null. */
    private fun requestJson(request: EventRequest): ObjectNode = jsonMapper.valueToTree(request)

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

    private fun send(
        method: String,
        uri: String,
        body: ObjectNode,
        expected: Int = 200
    ): EventResponse {
        val spec = if (method == "POST") webTestClient.post() else webTestClient.put()
        return spec
            .uri(uri)
            .bodyValue(body.toString())
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus()
            .isEqualTo(expected)
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!
    }
}

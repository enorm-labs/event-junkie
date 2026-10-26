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

/** Where a relocated or postponed show went (#3994): set, kept and cleared through the PUT, and pinned as `status`. */
class EventRelocationTest : BaseControllerTest() {
    private val jsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `PUT event names where a relocated show went, keeps it when left out, and clears it when the show did not move`() {
        val request = EventRequestFixtures.create(venueId = createVenue("Relocation Venue").id, sourceId = "test:put-relocated")
        val created = createEvent(requestJson(request))
        val moved = requestJson(request).put("status", "RELOCATED")

        putEvent(created.id, moved.deepCopy().put("relocatedTo", " Huxleys ").put("relocatedOn", "2027-03-12")).also {
            it.relocatedTo shouldBe "Huxleys"
            it.relocatedOn shouldBe LocalDate.of(2027, 3, 12)
            it.pinnedFields shouldBe listOf("status")
        }

        // A form that does not know the fields, or a script, keeps the destination.
        putEvent(created.id, moved.deepCopy().put("title", "Edited")).relocatedTo shouldBe "Huxleys"
        putEvent(created.id, moved.deepCopy().putNull("relocatedOn")).also {
            it.relocatedTo shouldBe "Huxleys"
            it.relocatedOn shouldBe null
        }

        putEvent(created.id, requestJson(request).put("title", "Edited")).also {
            it.status shouldBe EventStatus.SCHEDULED
            it.relocatedTo shouldBe null
        }
    }

    @Test
    fun `PUT event refuses a destination on a show that did not move`() {
        val request = EventRequestFixtures.create(venueId = createVenue("Unmoved Venue").id, sourceId = "test:put-unmoved")
        val created = createEvent(requestJson(request))

        webTestClient
            .put()
            .uri("/api/admin/events/${created.id}")
            .bodyValue(requestJson(request).put("relocatedTo", "Huxleys").toString())
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

    private fun createEvent(body: ObjectNode): EventResponse =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(body.toString())
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private fun putEvent(
        id: Long,
        body: ObjectNode
    ): EventResponse =
        webTestClient
            .put()
            .uri("/api/admin/events/$id")
            .bodyValue(body.toString())
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!
}

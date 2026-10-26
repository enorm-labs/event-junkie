package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.math.BigDecimal

/** `PATCH /api/admin/events/{id}` (#3995): one field changes and pins, the rest stays as stored. */
class EventPatchTest : BaseControllerTest() {
    @Test
    fun `PATCH changes one field, pins only it, and keeps the rest`() {
        val created = createEvent("test:patch-one")

        patch(created.id, """{"eventType":"OTHER"}""").expectStatus().isOk

        getEvent(created.id).also {
            it.eventType shouldBe EventType.OTHER
            it.pinnedFields shouldBe listOf("eventType")
            it.title shouldBe created.title
            it.pricePresale shouldBe created.pricePresale
            it.promoterIds shouldBe created.promoterIds
            it.slug shouldBe created.slug
        }
    }

    @Test
    fun `PATCH with an explicit null clears a clearable field`() {
        val created = createEvent("test:patch-clear")
        created.pricePresale shouldBe BigDecimal("38.00")

        patch(created.id, """{"pricePresale":null}""").expectStatus().isOk

        getEvent(created.id).also {
            it.pricePresale shouldBe null
            it.pinnedFields shouldBe listOf("pricePresale")
        }
    }

    @Test
    fun `PATCH names a move in one call`() {
        val created = createEvent("test:patch-move")

        patch(created.id, """{"status":"RELOCATED","relocatedTo":"Huxleys"}""").expectStatus().isOk

        getEvent(created.id).also {
            it.status shouldBe EventStatus.RELOCATED
            it.relocatedTo shouldBe "Huxleys"
            it.pinnedFields shouldBe listOf("status")
        }
    }

    @Test
    fun `PATCH refuses the identity key, an unknown field and a blank title`() {
        val created = createEvent("test:patch-refused")

        patch(created.id, """{"sourceId":"manual:other"}""").expectStatus().isBadRequest
        patch(created.id, """{"setStart":"2026-06-12T20:00:00Z"}""").expectStatus().isBadRequest
        patch(created.id, """{"title":""}""").expectStatus().isBadRequest
        getEvent(created.id).pinnedFields shouldBe emptyList()
    }

    @Test
    fun `PATCH of an unknown event is a 404`() {
        patch(999_999, """{"eventType":"OTHER"}""").expectStatus().isNotFound
    }

    private fun patch(
        id: Long,
        body: String
    ): WebTestClient.ResponseSpec =
        webTestClient
            .patch()
            .uri("/api/admin/events/$id")
            .bodyValue(body)
            .header("Content-Type", "application/json")
            .exchange()

    private fun createEvent(sourceId: String): EventResponse {
        val venue =
            webTestClient
                .post()
                .uri("/api/admin/venues")
                .bodyValue(VenueRequestFixtures.create(name = sourceId))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody<VenueResponse>()
                .returnResult()
                .responseBody!!
        return webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(EventRequestFixtures.create(venueId = venue.id, sourceId = sourceId, pricePresale = BigDecimal("38.00")))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!
    }

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

package de.norm.events.instagram

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventRequestFixtures
import de.norm.events.event.EventResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate
import java.time.OffsetDateTime

/** The review routes against a real database: a night is judged as a whole, and a judged draft keeps no caption. */
class InstagramDraftControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var repository: InstagramDraftRepository

    private fun createVenue(): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.create(name = "Draft Venue"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun post(
        id: String,
        caption: String = "XTRUDE on Saturday"
    ) = InstagramPost(id, caption, "https://www.instagram.com/p/$id/", OffsetDateTime.parse("2026-10-13T16:00:12Z"), "IMAGE")

    private fun event(date: String) = DraftedEvent(date = LocalDate.parse(date), title = "XTRUDE", evidence = "on Saturday", confidence = 0.9)

    /** Two posts announce the 17th, one the 24th. */
    private suspend fun seed(venueId: Long) {
        repository.insert(listOf(NewDraft(venueId, post("a"), DraftStatus.PENDING, event("2026-10-17"))))
        repository.insert(listOf(NewDraft(venueId, post("b"), DraftStatus.PENDING, event("2026-10-17"))))
        repository.insert(listOf(NewDraft(venueId, post("c"), DraftStatus.PENDING, event("2026-10-24"))))
    }

    private suspend fun draftId(mediaId: String): Long =
        databaseClient
            .sql("SELECT id FROM events.instagram_draft WHERE media_id = '$mediaId'")
            .map { row, _ -> (row.get("id", Number::class.java) ?: error("no id")).toLong() }
            .one()
            .awaitSingle()

    @Test
    fun `lists pending drafts, the soonest night first, with the caption`() =
        runTest {
            seed(createVenue().id)

            webTestClient
                .get()
                .uri("/api/admin/instagram-drafts")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.content[0].eventDate")
                .isEqualTo("2026-10-17")
                .jsonPath("$.content[2].eventDate")
                .isEqualTo("2026-10-24")
                .jsonPath("$.content[0].caption")
                .isEqualTo("XTRUDE on Saturday")
        }

    @Test
    fun `approving one draft approves its night, links the event and drops the captions`() =
        runTest {
            val venue = createVenue()
            seed(venue.id)
            val created =
                webTestClient
                    .post()
                    .uri("/api/admin/events")
                    .bodyValue(EventRequestFixtures.adicts(venueId = venue.id, eventDate = LocalDate.of(2026, 10, 17)))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody<EventResponse>()
                    .returnResult()
                    .responseBody!!

            webTestClient
                .post()
                .uri("/api/admin/instagram-drafts/${draftId("a")}/approve")
                .bodyValue(mapOf("eventId" to created.id))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.drafts")
                .isEqualTo(2)

            val b = repository.findById(draftId("b"))
            b?.status shouldBe DraftStatus.APPROVED
            b?.eventId shouldBe created.id
            b?.caption shouldBe null
            repository.findById(draftId("c"))?.status shouldBe DraftStatus.PENDING
        }

    @Test
    fun `rejecting twice is a 409, and an unknown draft a 404`() =
        runTest {
            seed(createVenue().id)
            val id = draftId("c")

            webTestClient
                .post()
                .uri("/api/admin/instagram-drafts/$id/reject")
                .exchange()
                .expectStatus()
                .isOk
            webTestClient
                .post()
                .uri("/api/admin/instagram-drafts/$id/reject")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
            webTestClient
                .post()
                .uri("/api/admin/instagram-drafts/999999/reject")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    @Test
    fun `switching a venue off deletes its drafts, and switching it on lists it`() =
        runTest {
            val venue = createVenue()
            seed(venue.id)

            webTestClient
                .put()
                .uri("/api/admin/instagram/venues/${venue.slug}")
                .exchange()
                .expectStatus()
                .isOk
            webTestClient
                .get()
                .uri("/api/admin/instagram/venues")
                .exchange()
                .expectBody()
                .jsonPath("$[0].slug")
                .isEqualTo(venue.slug)
            webTestClient
                .delete()
                .uri("/api/admin/instagram/venues/${venue.slug}")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.draftsDeleted")
                .isEqualTo(3)

            repository.count(DraftStatus.PENDING) shouldBe 0
        }
}

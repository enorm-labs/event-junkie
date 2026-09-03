package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRequestFixtures
import de.norm.events.artist.ArtistResponse
import de.norm.events.common.PageResponse
import de.norm.events.promoter.PromoterRequestFixtures
import de.norm.events.promoter.PromoterResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import java.math.BigDecimal

/**
 * End-to-end integration test that mirrors the `full-lifecycle.http` scenario.
 *
 * Exercises the complete CRUD flow across all entity types: creates a venue,
 * two artists, and a promoter, then creates an event with all associations,
 * verifies it via list and get, updates it, deletes it, and finally cleans
 * up all dependent entities — asserting correct HTTP status codes and response
 * bodies at every step.
 */
class FullLifecycleIntegrationTest : BaseControllerTest() {
    @Test
    @Suppress("LongMethod") // Intentionally long — exercises the complete end-to-end CRUD flow in a single sequential scenario
    fun `full CRUD lifecycle — venue, artists, promoter, event`() {
        // --- Step 1: Create venue ---
        val venue =
            webTestClient
                .post()
                .uri("/api/admin/venues")
                .bodyValue(
                    VenueRequestFixtures.astra(
                        latitude = BigDecimal("52.507242"),
                        longitude = BigDecimal("13.451803")
                    )
                ).exchange()
                .expectStatus()
                .isCreated
                .expectBody<VenueResponse>()
                .returnResult()
                .responseBody!!

        venue.name shouldBe "Astra Kulturhaus"

        // --- Step 2: Create headliner artist ---
        val headliner =
            webTestClient
                .post()
                .uri("/api/admin/artists")
                .bodyValue(
                    ArtistRequestFixtures.adicts(
                        description = "Legendary punk band from Ipswich, England."
                    )
                ).exchange()
                .expectStatus()
                .isCreated
                .expectBody<ArtistResponse>()
                .returnResult()
                .responseBody!!

        headliner.name shouldBe "The Adicts"

        // --- Step 3: Create support artist ---
        val support =
            webTestClient
                .post()
                .uri("/api/admin/artists")
                .bodyValue(
                    ArtistRequestFixtures.create(
                        name = "Maid of Ace",
                        description = "All-female punk band from Hastings, UK."
                    )
                ).exchange()
                .expectStatus()
                .isCreated
                .expectBody<ArtistResponse>()
                .returnResult()
                .responseBody!!

        support.name shouldBe "Maid of Ace"

        // --- Step 4: Create promoter ---
        val promoter =
            webTestClient
                .post()
                .uri("/api/admin/promoters")
                .bodyValue(PromoterRequestFixtures.concerts36())
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody<PromoterResponse>()
                .returnResult()
                .responseBody!!

        promoter.name shouldBe "36 Concerts"

        // --- Step 5: Create event with all associations ---
        val event =
            webTestClient
                .post()
                .uri("/api/admin/events")
                .bodyValue(
                    EventRequestFixtures.adicts(
                        venueId = venue.id,
                        subtitle = "Adios Amigos Tour 2026 + Support: MAID OF ACE",
                        sourceUrl = "https://www.astra-berlin.de/events/2026-06-12-the-adicts",
                        artists =
                            listOf(
                                EventArtistRequest(artistId = headliner.id, role = ArtistRole.HEADLINER, billingOrder = 0),
                                EventArtistRequest(artistId = support.id, role = ArtistRole.SUPPORT, billingOrder = 1)
                            ),
                        promoterIds = listOf(promoter.id)
                    )
                ).exchange()
                .expectStatus()
                .isCreated
                .expectBody<EventResponse>()
                .returnResult()
                .responseBody!!

        event.title shouldBe "THE ADICTS"
        event.venueId shouldBe venue.id
        event.soldOut shouldBe false
        event.artists shouldHaveSize 2
        event.artists[0].role shouldBe ArtistRole.HEADLINER
        event.artists[1].role shouldBe ArtistRole.SUPPORT
        event.promoterIds shouldHaveSize 1

        // --- Step 6: Verify — list all events ---
        val events =
            webTestClient
                .get()
                .uri("/api/admin/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<EventResponse>>()
                .returnResult()
                .responseBody!!

        events.size shouldBeGreaterThanOrEqual 1

        // --- Step 7: Verify — get event by ID ---
        webTestClient
            .get()
            .uri("/api/admin/events/${event.id}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .consumeWith { result ->
                val fetched = result.responseBody!!
                fetched.title shouldBe "THE ADICTS"
                fetched.artists shouldHaveSize 2
                fetched.promoterIds shouldHaveSize 1
            }

        // --- Step 8: Update event — mark as sold out, drop support artist ---
        val updateRequest =
            EventRequestFixtures.adicts(
                venueId = venue.id,
                subtitle = "Adios Amigos Tour 2026 + Support: MAID OF ACE",
                soldOut = true,
                artists =
                    listOf(
                        EventArtistRequest(artistId = headliner.id, role = ArtistRole.HEADLINER, billingOrder = 0)
                    ),
                promoterIds = listOf(promoter.id)
            )

        val updated =
            webTestClient
                .put()
                .uri("/api/admin/events/${event.id}")
                .bodyValue(updateRequest)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<EventResponse>()
                .returnResult()
                .responseBody!!

        updated.soldOut shouldBe true
        updated.artists shouldHaveSize 1

        // --- Step 9: Delete event ---
        webTestClient
            .delete()
            .uri("/api/admin/events/${event.id}")
            .exchange()
            .expectStatus()
            .isNoContent

        // --- Step 10: Verify deletion (expect 404) ---
        webTestClient
            .get()
            .uri("/api/admin/events/${event.id}")
            .exchange()
            .expectStatus()
            .isNotFound

        // --- Cleanup: Delete promoter ---
        webTestClient
            .delete()
            .uri("/api/admin/promoters/${promoter.id}")
            .exchange()
            .expectStatus()
            .isNoContent

        // --- Cleanup: Delete support artist ---
        webTestClient
            .delete()
            .uri("/api/admin/artists/${support.id}")
            .exchange()
            .expectStatus()
            .isNoContent

        // --- Cleanup: Delete headliner artist ---
        webTestClient
            .delete()
            .uri("/api/admin/artists/${headliner.id}")
            .exchange()
            .expectStatus()
            .isNoContent

        // --- Cleanup: Delete venue ---
        webTestClient
            .delete()
            .uri("/api/admin/venues/${venue.id}")
            .exchange()
            .expectStatus()
            .isNoContent
    }
}

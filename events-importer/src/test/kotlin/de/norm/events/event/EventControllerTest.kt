package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRequest
import de.norm.events.artist.ArtistRequestFixtures
import de.norm.events.artist.ArtistResponse
import de.norm.events.common.PageResponse
import de.norm.events.promoter.PromoterRequest
import de.norm.events.promoter.PromoterRequestFixtures
import de.norm.events.promoter.PromoterResponse
import de.norm.events.venue.VenueRequest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class EventControllerTest : BaseControllerTest() {
    // -- Helper methods for seeding dependent entities --

    private fun createVenue(request: VenueRequest = VenueRequestFixtures.astra()): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun createArtist(request: ArtistRequest = ArtistRequestFixtures.adicts()): ArtistResponse =
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    private fun createPromoter(request: PromoterRequest = PromoterRequestFixtures.concerts36()): PromoterResponse =
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<PromoterResponse>()
            .returnResult()
            .responseBody!!

    // -- Helper methods for events --

    private fun createEvent(request: EventRequest): EventResponse =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private fun deleteEvent(id: Long) {
        webTestClient
            .delete()
            .uri("/api/admin/events/$id")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `POST event with artists and promoters creates event`() {
        val venue = createVenue()
        val artist = createArtist()
        val promoter = createPromoter()

        val request =
            EventRequestFixtures.adicts(
                venueId = venue.id,
                artists = listOf(EventArtistRequest(artistId = artist.id, role = ArtistRole.HEADLINER)),
                promoterIds = listOf(promoter.id)
            )
        val created = createEvent(request)

        created.venueId shouldBe venue.id
        created.title shouldBe "THE ADICTS"
        created.slug shouldBe "2026-06-12-astra-kulturhaus-the-adicts"
        created.genre shouldBe "Punk"
        created.genreTags shouldContainExactlyInAnyOrder listOf("Punk")
        created.artists shouldHaveSize 1
        created.artists[0].artistId shouldBe artist.id
        created.artists[0].role shouldBe ArtistRole.HEADLINER
        created.promoterIds shouldHaveSize 1
        created.promoterIds[0] shouldBe promoter.id
    }

    @Test
    fun `GET event returns previously created event`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "GET Venue"))
        val artist = createArtist(ArtistRequestFixtures.create(name = "GET Artist"))
        val promoter = createPromoter(PromoterRequestFixtures.create(name = "GET Promoter"))

        val created =
            createEvent(
                EventRequestFixtures.adicts(
                    venueId = venue.id,
                    artists = listOf(EventArtistRequest(artistId = artist.id, role = ArtistRole.HEADLINER)),
                    promoterIds = listOf(promoter.id),
                    sourceId = "test:get-lifecycle"
                )
            )

        webTestClient
            .get()
            .uri("/api/admin/events/${created.id}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .consumeWith { result ->
                val event = result.responseBody!!
                event.title shouldBe "THE ADICTS"
                event.artists shouldHaveSize 1
                event.promoterIds shouldHaveSize 1
            }
    }

    @Test
    fun `PUT event updates title and artists`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "PUT Venue"))
        val artist = createArtist(ArtistRequestFixtures.create(name = "PUT Artist"))
        val promoter = createPromoter(PromoterRequestFixtures.create(name = "PUT Promoter"))
        val sourceId = "test:put-lifecycle"

        val created =
            createEvent(
                EventRequestFixtures.adicts(
                    venueId = venue.id,
                    artists = listOf(EventArtistRequest(artistId = artist.id, role = ArtistRole.HEADLINER)),
                    promoterIds = listOf(promoter.id),
                    sourceId = sourceId
                )
            )

        val supportArtist = createArtist(ArtistRequestFixtures.create(name = "Maid of Ace"))
        val updateRequest =
            EventRequestFixtures.adicts(
                venueId = venue.id,
                title = "THE ADICTS + MAID OF ACE",
                sourceId = sourceId,
                artists =
                    listOf(
                        EventArtistRequest(artistId = artist.id, role = ArtistRole.HEADLINER, billingOrder = 0),
                        EventArtistRequest(artistId = supportArtist.id, role = ArtistRole.SUPPORT, billingOrder = 1)
                    ),
                promoterIds = listOf(promoter.id)
            )

        webTestClient
            .put()
            .uri("/api/admin/events/${created.id}")
            .bodyValue(updateRequest)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .consumeWith { result ->
                val event = result.responseBody!!
                event.title shouldBe "THE ADICTS + MAID OF ACE"
                event.artists shouldHaveSize 2
                event.artists.map { it.artistId } shouldContain supportArtist.id
            }
    }

    @Test
    fun `DELETE event removes it`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "DELETE Venue"))
        val created =
            createEvent(
                EventRequestFixtures.create(
                    venueId = venue.id,
                    sourceId = "test:delete-lifecycle"
                )
            )

        deleteEvent(created.id)

        webTestClient
            .get()
            .uri("/api/admin/events/${created.id}")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST event without artists or promoters`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Minimal Venue"))

        val request =
            EventRequestFixtures.create(
                venueId = venue.id,
                sourceId = "test:minimal-event",
                title = "Minimal Event"
            )
        val created = createEvent(request)

        created.title shouldBe "Minimal Event"
        created.artists shouldHaveSize 0
        created.promoterIds shouldHaveSize 0
    }

    @Test
    fun `POST event with non-existent venue returns 404`() {
        val request =
            EventRequestFixtures.create(
                venueId = 99999,
                sourceId = "test:no-venue"
            )
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET event by non-existent ID returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/events/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET all events returns list`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "List Test Venue"))
        val created =
            createEvent(
                EventRequestFixtures.create(
                    venueId = venue.id,
                    sourceId = "test:list-event"
                )
            )

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

        // `.content`, never `.size`: on the envelope that field is the *page* size, so an assertion
        // written against it passes whatever the listing returned (#810).
        events.content.size shouldBeGreaterThanOrEqual 1
        events.content.map { it.id } shouldContain created.id
        // Everything created here fits on one page, so the total must equal what came back.
        events.totalElements shouldBe events.content.size.toLong()
    }

    @Test
    fun `POST event with non-existent artist returns 404`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Artist 404 Venue"))

        val request =
            EventRequestFixtures.create(
                venueId = venue.id,
                sourceId = "test:bad-artist",
                artists = listOf(EventArtistRequest(artistId = 99999, role = ArtistRole.HEADLINER))
            )
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST event with non-existent promoter returns 404`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Promoter 404 Venue"))

        val request =
            EventRequestFixtures.create(
                venueId = venue.id,
                sourceId = "test:bad-promoter",
                promoterIds = listOf(99999)
            )
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST event with duplicate sourceId returns 409`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Duplicate Venue"))
        val sourceId = "test:duplicate-source"

        createEvent(EventRequestFixtures.create(venueId = venue.id, sourceId = sourceId))

        // Second event with the same sourceId should conflict
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(EventRequestFixtures.create(venueId = venue.id, sourceId = sourceId))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `PUT non-existent event returns 404`() {
        webTestClient
            .put()
            .uri("/api/admin/events/99999")
            .bodyValue(EventRequestFixtures.create(venueId = 1, sourceId = "test:no-event"))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `DELETE non-existent event returns 404`() {
        webTestClient
            .delete()
            .uri("/api/admin/events/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST event with blank title returns 400`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Validation Title Venue"))

        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:blank-title", title = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST event with blank sourceId returns 400`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Validation SourceId Venue"))

        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(EventRequestFixtures.create(venueId = venue.id, sourceId = "", title = "Valid Title"))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `PUT event with blank title returns 400`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Validation PUT Venue"))
        val created = createEvent(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:put-blank-title"))

        webTestClient
            .put()
            .uri("/api/admin/events/${created.id}")
            .bodyValue(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:put-blank-title", title = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}

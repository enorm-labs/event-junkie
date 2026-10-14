package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRequestFixtures
import de.norm.events.artist.ArtistResponse
import de.norm.events.common.PageResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

/** The filters of `GET /api/admin/events`: `venueId`, `date` and `artistId`, alone and together. */
class EventListFilterTest : BaseControllerTest() {
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

    private fun createArtist(name: String): ArtistResponse =
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = name))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

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

    private fun idsWhere(query: String): Pair<List<Long>, Long> {
        val page =
            webTestClient
                .get()
                .uri("/api/admin/events?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<EventResponse>>()
                .returnResult()
                .responseBody!!
        return page.content.map { it.id } to page.totalElements
    }

    // #347: the New event form asks which events a venue already holds on a date before it saves.
    @Test
    fun `GET events filters by venue, by date, by both and by neither`() {
        val lido = createVenue("Filter Lido")
        val astra = createVenue("Filter Astra")
        val friday = LocalDate.of(2026, 10, 9)
        val saturday = LocalDate.of(2026, 10, 10)
        val lidoFriday = createEvent(EventRequestFixtures.create(venueId = lido.id, sourceId = "test:lido-fri", eventDate = friday))
        val lidoSaturday = createEvent(EventRequestFixtures.create(venueId = lido.id, sourceId = "test:lido-sat", eventDate = saturday))
        val astraSaturday = createEvent(EventRequestFixtures.create(venueId = astra.id, sourceId = "test:astra-sat", eventDate = saturday))

        assertSoftly {
            idsWhere("venueId=${lido.id}") shouldBe (listOf(lidoFriday.id, lidoSaturday.id) to 2L)
            idsWhere("date=2026-10-10").first shouldContainExactlyInAnyOrder listOf(lidoSaturday.id, astraSaturday.id)
            idsWhere("date=2026-10-10").second shouldBe 2L
            idsWhere("venueId=${lido.id}&date=2026-10-10") shouldBe (listOf(lidoSaturday.id) to 1L)
            idsWhere("size=50").first shouldContainExactlyInAnyOrder listOf(lidoFriday.id, lidoSaturday.id, astraSaturday.id)
            idsWhere("venueId=${lido.id}&date=2026-10-11") shouldBe (emptyList<Long>() to 0L)
        }
    }

    // #3005: the Artists page shows the events that bill an AMBIGUOUS act.
    @Test
    fun `GET events filters by artist, alone and with venue and date`() {
        val lido = createVenue("Artist Filter Lido")
        val astra = createVenue("Artist Filter Astra")
        val billed = createArtist("Artist Filter Billed")
        val other = createArtist("Artist Filter Other")
        val unbilled = createArtist("Artist Filter Unbilled")
        val friday = LocalDate.of(2026, 10, 9)
        val saturday = LocalDate.of(2026, 10, 10)

        fun lineup(vararg artistIds: Long) =
            artistIds.mapIndexed { index, id -> EventArtistRequest(artistId = id, role = ArtistRole.HEADLINER, billingOrder = index) }

        val lidoFriday =
            createEvent(
                EventRequestFixtures.create(venueId = lido.id, sourceId = "test:artist-lido-fri", eventDate = friday, artists = lineup(billed.id, other.id))
            )
        val lidoSaturday =
            createEvent(EventRequestFixtures.create(venueId = lido.id, sourceId = "test:artist-lido-sat", eventDate = saturday, artists = lineup(billed.id)))
        val astraSaturday =
            createEvent(EventRequestFixtures.create(venueId = astra.id, sourceId = "test:artist-astra-sat", eventDate = saturday, artists = lineup(billed.id)))
        createEvent(EventRequestFixtures.create(venueId = astra.id, sourceId = "test:artist-astra-fri", eventDate = friday, artists = lineup(other.id)))

        assertSoftly {
            idsWhere("artistId=${billed.id}&sort=eventDate").second shouldBe 3L
            idsWhere("artistId=${billed.id}&sort=eventDate").first shouldContainExactlyInAnyOrder
                listOf(lidoFriday.id, lidoSaturday.id, astraSaturday.id)
            idsWhere("artistId=${billed.id}&size=1").second shouldBe 3L
            idsWhere("artistId=${unbilled.id}") shouldBe (emptyList<Long>() to 0L)
            idsWhere("artistId=99999") shouldBe (emptyList<Long>() to 0L)
            idsWhere("artistId=${billed.id}&venueId=${lido.id}") shouldBe (listOf(lidoFriday.id, lidoSaturday.id) to 2L)
            idsWhere("artistId=${billed.id}&date=2026-10-10").first shouldContainExactlyInAnyOrder listOf(lidoSaturday.id, astraSaturday.id)
            idsWhere("artistId=${billed.id}&venueId=${astra.id}&date=2026-10-10") shouldBe (listOf(astraSaturday.id) to 1L)
            idsWhere("artistId=${billed.id}&venueId=${astra.id}&date=2026-10-09") shouldBe (emptyList<Long>() to 0L)
        }
    }

    @Test
    fun `GET events for an unknown venue returns an empty page, not 404`() {
        val venue = createVenue("Filter Known Venue")
        createEvent(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:known-venue"))

        val page =
            webTestClient
                .get()
                .uri("/api/admin/events?venueId=99999")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<EventResponse>>()
                .returnResult()
                .responseBody!!

        page.content shouldHaveSize 0
        page.totalElements shouldBe 0L
    }

    @Test
    fun `GET events with a date that is not ISO returns a 400 Problem Detail`() {
        webTestClient
            .get()
            .uri("/api/admin/events?date=10.10.2026")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(400)
            .jsonPath("$.detail")
            .value<String> { it shouldContain "10.10.2026" }
    }
}

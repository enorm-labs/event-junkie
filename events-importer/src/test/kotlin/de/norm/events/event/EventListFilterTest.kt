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
import java.time.LocalTime

/** The filters of `GET /api/admin/events`: `venueId`, `date`, `artistId`, `q` and `from`, alone and together. */
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

    /** The ids and the total for title search [term], sent as a URI variable so `%` and the backslash reach the importer encoded. */
    private fun idsMatching(term: String): Pair<List<Long>, Long> {
        val page =
            webTestClient
                .get()
                .uri { it.path("/api/admin/events").queryParam("q", "{q}").build(term) }
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<EventResponse>>()
                .returnResult()
                .responseBody!!
        return page.content.map { it.id } to page.totalElements
    }

    // #3269: the Events page of events-admin finds an event by a part of its title.
    @Test
    fun `GET events filters by a part of the title in any case, with percent and underscore taken literally`() {
        val venue = createVenue("Title Filter Lido")

        fun titled(
            title: String,
            sourceId: String
        ) = createEvent(EventRequestFixtures.create(venueId = venue.id, title = title, sourceId = sourceId))

        val openStage = titled("Open Stage Night", "test:q-open-stage")
        val percent = titled("100% Techno", "test:q-percent")
        val underscore = titled("dj_set live", "test:q-underscore")
        val plain = titled("100 Techno Tracks", "test:q-plain")
        val longTitle = titled("A".repeat(240) + " Late Finale", "test:q-long")

        assertSoftly {
            idsMatching("open STAGE") shouldBe (listOf(openStage.id) to 1L)
            idsMatching("0%").first shouldBe listOf(percent.id)
            idsMatching("%").first shouldBe listOf(percent.id)
            idsMatching("j_s").first shouldBe listOf(underscore.id)
            idsMatching("_").first shouldBe listOf(underscore.id)
            idsMatching("techno").first shouldContainExactlyInAnyOrder listOf(percent.id, plain.id)
            idsMatching("late finale") shouldBe (listOf(longTitle.id) to 1L)
            idsMatching("\\") shouldBe (emptyList<Long>() to 0L)
            idsMatching("nowhere") shouldBe (emptyList<Long>() to 0L)
            idsMatching("  ").second shouldBe 5L
            idsWhere("q=").second shouldBe 5L
        }
    }

    // #3269: the Events page hides past events unless the operator asks for them.
    @Test
    fun `GET events from a date drops earlier events and keeps one over midnight on its start day`() {
        val venue = createVenue("From Filter Lido")
        val thursday = createEvent(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:from-thu", eventDate = LocalDate.of(2026, 10, 8)))
        // Starts late on Friday and ends on Saturday: it belongs to Friday.
        val overMidnight =
            createEvent(
                EventRequestFixtures.create(
                    venueId = venue.id,
                    sourceId = "test:from-fri",
                    eventDate = LocalDate.of(2026, 10, 9),
                    startTime = LocalTime.of(23, 30)
                )
            )
        val saturday = createEvent(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:from-sat", eventDate = LocalDate.of(2026, 10, 10)))

        assertSoftly {
            idsWhere("from=2026-10-09&sort=eventDate") shouldBe (listOf(overMidnight.id, saturday.id) to 2L)
            idsWhere("from=2026-10-10") shouldBe (listOf(saturday.id) to 1L)
            idsWhere("from=2026-10-11") shouldBe (emptyList<Long>() to 0L)
            idsWhere("from=2026-10-01&sort=eventDate") shouldBe (listOf(thursday.id, overMidnight.id, saturday.id) to 3L)
        }
    }

    @Test
    fun `GET events combines q and from with each other and with venue, date and artist`() {
        val lido = createVenue("Combined Filter Lido")
        val astra = createVenue("Combined Filter Astra")
        val act = createArtist("Combined Filter Act")
        val friday = LocalDate.of(2026, 10, 9)
        val saturday = LocalDate.of(2026, 10, 10)
        val billing = listOf(EventArtistRequest(artistId = act.id, role = ArtistRole.HEADLINER, billingOrder = 0))

        val lidoFriday =
            createEvent(EventRequestFixtures.create(venueId = lido.id, title = "Jam Session", sourceId = "test:c-lido-fri", eventDate = friday))
        val lidoSaturday =
            createEvent(
                EventRequestFixtures.create(venueId = lido.id, title = "Jam Session", sourceId = "test:c-lido-sat", eventDate = saturday, artists = billing)
            )
        val astraSaturday =
            createEvent(EventRequestFixtures.create(venueId = astra.id, title = "Late JAM", sourceId = "test:c-astra-sat", eventDate = saturday))
        createEvent(EventRequestFixtures.create(venueId = astra.id, title = "Quiz Night", sourceId = "test:c-astra-quiz", eventDate = saturday))

        assertSoftly {
            idsWhere("q=jam&from=2026-10-10").first shouldContainExactlyInAnyOrder listOf(lidoSaturday.id, astraSaturday.id)
            idsWhere("q=jam&from=2026-10-10").second shouldBe 2L
            idsWhere("q=jam&venueId=${lido.id}&sort=eventDate") shouldBe (listOf(lidoFriday.id, lidoSaturday.id) to 2L)
            idsWhere("q=jam&date=2026-10-09") shouldBe (listOf(lidoFriday.id) to 1L)
            idsWhere("q=jam&artistId=${act.id}") shouldBe (listOf(lidoSaturday.id) to 1L)
            idsWhere("from=2026-10-10&venueId=${lido.id}") shouldBe (listOf(lidoSaturday.id) to 1L)
            idsWhere("from=2026-10-10&artistId=${act.id}") shouldBe (listOf(lidoSaturday.id) to 1L)
            idsWhere("from=2026-10-10&date=2026-10-09") shouldBe (emptyList<Long>() to 0L)
            idsWhere("q=jam&from=2026-10-09&venueId=${astra.id}&date=2026-10-10") shouldBe (listOf(astraSaturday.id) to 1L)
            idsWhere("q=quiz&artistId=${act.id}") shouldBe (emptyList<Long>() to 0L)
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

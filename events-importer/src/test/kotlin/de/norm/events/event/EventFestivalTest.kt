package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import de.norm.events.festival.FestivalRequest
import de.norm.events.festival.FestivalResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate

/** An event's festival (#3273): set, kept and cleared through the PUT, the `festivalId` filter, and the festival's count. */
class EventFestivalTest : BaseControllerTest() {
    private val jsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

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

    private fun createFestival(
        name: String,
        firstDay: LocalDate = LocalDate.of(2027, 6, 21),
        lastDay: LocalDate = firstDay
    ): FestivalResponse =
        webTestClient
            .post()
            .uri("/api/admin/festivals")
            .bodyValue(
                FestivalRequest(
                    name = name,
                    url = "https://festival.example/",
                    firstDay = firstDay,
                    lastDay = lastDay,
                    noticeDe = "Auf Deutsch.",
                    noticeEn = "In English."
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody<FestivalResponse>()
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

    /** The request as the wire carries it, so a test can tell a missing field from an explicit null. */
    private fun json(request: EventRequest): ObjectNode = jsonMapper.valueToTree(request)

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

    private fun festivals(): List<FestivalResponse> =
        webTestClient
            .get()
            .uri("/api/admin/festivals?size=100")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<PageResponse<FestivalResponse>>()
            .returnResult()
            .responseBody!!
            .content

    @Test
    fun `PUT sets the festival, a PUT without the field keeps it, and an explicit null clears it`() {
        val venue = createVenue("Festival Venue")
        val festival = createFestival("Fête de la Musique 2027")
        val request = EventRequestFixtures.create(venueId = venue.id, sourceId = "test:festival-put")
        val created = createEvent(json(request))
        created.festivalId shouldBe null

        putEvent(created.id, json(request).put("festivalId", festival.id)).also {
            it.festivalId shouldBe festival.id
            // A link is no correction of the source's data, so it pins nothing.
            it.pinnedFields shouldBe emptyList()
        }

        putEvent(created.id, json(request).apply { remove("festivalId") }.put("title", "Edited")).festivalId shouldBe festival.id
        getEvent(created.id).festivalId shouldBe festival.id

        putEvent(created.id, json(request).putNull("festivalId")).festivalId shouldBe null
        getEvent(created.id).festivalId shouldBe null
    }

    @Test
    fun `POST and PUT with an unknown festival are a 404 and change nothing`() {
        val venue = createVenue("Unknown Festival Venue")
        val request = EventRequestFixtures.create(venueId = venue.id, sourceId = "test:festival-unknown")

        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(json(request).put("festivalId", 99999).toString())
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus()
            .isNotFound

        val created = createEvent(json(request))
        webTestClient
            .put()
            .uri("/api/admin/events/${created.id}")
            .bodyValue(json(request).put("festivalId", 99999).put("title", "Not saved").toString())
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus()
            .isNotFound
        getEvent(created.id).title shouldBe request.title
    }

    @Test
    fun `GET events filters by festival, alone and with the other filters, and the festival counts its events`() {
        val lido = createVenue("Festival Filter Lido")
        val astra = createVenue("Festival Filter Astra")
        val fete = createFestival("Filter Fête", LocalDate.of(2027, 6, 21))
        val week = createFestival("Filter Week", LocalDate.of(2027, 6, 19), LocalDate.of(2027, 6, 27))
        val friday = LocalDate.of(2027, 6, 18)
        val monday = LocalDate.of(2027, 6, 21)

        fun event(
            venue: VenueResponse,
            sourceId: String,
            date: LocalDate,
            title: String = "Open Air",
            festivalId: Long? = null
        ) = createEvent(
            json(EventRequestFixtures.create(venueId = venue.id, sourceId = sourceId, eventDate = date, title = title))
                .also { body -> festivalId?.let { body.put("festivalId", it) } }
        )

        val lidoMonday = event(lido, "test:ff-lido-mon", monday, festivalId = fete.id)
        val astraMonday = event(astra, "test:ff-astra-mon", monday, title = "Late Jam", festivalId = fete.id)
        val astraWeek = event(astra, "test:ff-astra-week", monday.plusDays(2), festivalId = week.id)
        event(lido, "test:ff-lido-fri", friday)

        assertSoftly {
            idsWhere("festivalId=${fete.id}").first shouldContainExactlyInAnyOrder listOf(lidoMonday.id, astraMonday.id)
            idsWhere("festivalId=${fete.id}").second shouldBe 2L
            idsWhere("festivalId=${week.id}") shouldBe (listOf(astraWeek.id) to 1L)
            idsWhere("festivalId=99999") shouldBe (emptyList<Long>() to 0L)
            idsWhere("festivalId=${fete.id}&venueId=${astra.id}") shouldBe (listOf(astraMonday.id) to 1L)
            idsWhere("festivalId=${fete.id}&q=jam") shouldBe (listOf(astraMonday.id) to 1L)
            idsWhere("festivalId=${fete.id}&date=2027-06-18") shouldBe (emptyList<Long>() to 0L)
            idsWhere("festivalId=${week.id}&from=2027-06-22") shouldBe (listOf(astraWeek.id) to 1L)
            idsWhere("festivalId=${week.id}&from=2027-06-24") shouldBe (emptyList<Long>() to 0L)
            festivals().associate { it.name to it.eventCount } shouldBe mapOf("Filter Fête" to 2L, "Filter Week" to 1L)
        }
    }

    @Test
    fun `deleting a festival keeps its events and clears their link`() {
        val venue = createVenue("Deleted Festival Venue")
        val festival = createFestival("Deleted Festival")
        val created =
            createEvent(json(EventRequestFixtures.create(venueId = venue.id, sourceId = "test:festival-deleted")).put("festivalId", festival.id))
        created.festivalId shouldBe festival.id

        webTestClient
            .delete()
            .uri("/api/admin/festivals/${festival.id}")
            .exchange()
            .expectStatus()
            .isNoContent

        getEvent(created.id).festivalId shouldBe null
    }
}

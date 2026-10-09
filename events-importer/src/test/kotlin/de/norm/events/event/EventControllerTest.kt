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
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.expectBody
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

class EventControllerTest : BaseControllerTest() {
    private val jsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

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

    private fun putEvent(
        id: Long,
        request: EventRequest
    ) {
        webTestClient
            .put()
            .uri("/api/admin/events/$id")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isOk
    }

    // -- Helper methods for the importer-owned lineup columns (#3026) --

    private data class DerivedColumns(
        val titleDerived: Boolean,
        val setStart: Instant?,
        val setEnd: Instant?
    )

    private data class LineupRow(
        val role: String,
        val billingOrder: Int,
        val derived: DerivedColumns
    )

    /** What the "import" below writes onto lineup row [index]: a distinct value per row, so a swap shows. */
    private fun importedColumns(index: Int): DerivedColumns {
        val start = Instant.parse("2026-10-03T22:00:00Z").plusSeconds(index * 3600L)
        return DerivedColumns(titleDerived = true, setStart = start, setEnd = start.plusSeconds(3000))
    }

    /**
     * An event with a headliner and a support act whose `title_derived`, `set_start` and `set_end`
     * are set the way an import sets them; the admin API cannot write them.
     */
    private fun createEventWithImportedLineup(name: String): Triple<EventResponse, EventRequest, List<Long>> {
        val venue = createVenue(VenueRequestFixtures.create(name = "Lineup Venue $name"))
        val artists =
            listOf(
                createArtist(ArtistRequestFixtures.create(name = "Lineup Headliner $name")).id,
                createArtist(ArtistRequestFixtures.create(name = "Lineup Support $name")).id
            )
        val request =
            EventRequestFixtures.create(
                venueId = venue.id,
                sourceId = "test:put-lineup-$name",
                artists =
                    listOf(
                        EventArtistRequest(artistId = artists[0], role = ArtistRole.HEADLINER, billingOrder = 0),
                        EventArtistRequest(artistId = artists[1], role = ArtistRole.SUPPORT, billingOrder = 1)
                    )
            )
        val created = createEvent(request)
        runBlocking {
            artists.forEachIndexed { index, artistId ->
                val columns = importedColumns(index)
                databaseClient
                    .sql(
                        "UPDATE events.event_artist SET title_derived = true, set_start = :start, set_end = :end " +
                            "WHERE event_id = :event AND artist_id = :artist"
                    ).bind("start", columns.setStart!!)
                    .bind("end", columns.setEnd!!)
                    .bind("event", created.id)
                    .bind("artist", artistId)
                    .await()
            }
        }
        lineupRows(created.id).values.map { it.derived } shouldContainExactlyInAnyOrder listOf(importedColumns(0), importedColumns(1))
        return Triple(created, request, artists)
    }

    private fun lineupRows(eventId: Long): Map<Long, LineupRow> =
        runBlocking {
            databaseClient
                .sql("SELECT artist_id, role, billing_order, title_derived, set_start, set_end FROM events.event_artist WHERE event_id = :event")
                .bind("event", eventId)
                .fetch()
                .all()
                .collectList()
                .awaitSingle()
        }.associate { row ->
            (row["artist_id"] as Number).toLong() to
                LineupRow(
                    role = row["role"] as String,
                    billingOrder = (row["billing_order"] as Number).toInt(),
                    derived =
                        DerivedColumns(
                            titleDerived = row["title_derived"] as Boolean,
                            setStart = (row["set_start"] as OffsetDateTime?)?.toInstant(),
                            setEnd = (row["set_end"] as OffsetDateTime?)?.toInstant()
                        )
                )
        }

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
    fun `PUT event keeps the columns the importer derived`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Derived Venue"))
        val request = EventRequestFixtures.create(venueId = venue.id, sourceId = "test:put-derived")
        val created = createEvent(request)
        runBlocking {
            databaseClient
                .sql(
                    "UPDATE events.event SET room = 'Saal', end_date = event_date + 1, relocated_to = 'Hole44', " +
                        "description_withheld = true WHERE id = ${created.id}"
                ).await()
        }

        webTestClient
            .put()
            .uri("/api/admin/events/${created.id}")
            .bodyValue(request.copy(title = "Edited title"))
            .exchange()
            .expectStatus()
            .isOk

        val row =
            runBlocking {
                databaseClient
                    .sql("SELECT title, room, end_date - event_date AS nights, relocated_to, description_withheld FROM events.event WHERE id = ${created.id}")
                    .fetch()
                    .one()
                    .awaitSingle()
            }
        row["title"] shouldBe "Edited title"
        row["room"] shouldBe "Saal"
        row["nights"] shouldBe 1
        row["relocated_to"] shouldBe "Hole44"
        row["description_withheld"] shouldBe true
    }

    @Test
    fun `PUT event with the lineup unchanged keeps each row's title_derived and set times`() {
        val (created, request, artists) = createEventWithImportedLineup("unchanged")

        putEvent(created.id, request.copy(title = "Edited title"))

        val rows = lineupRows(created.id)
        rows.keys shouldBe artists.toSet()
        artists.forEachIndexed { index, artistId -> rows.getValue(artistId).derived shouldBe importedColumns(index) }
    }

    @Test
    fun `PUT event gives an added artist the defaults and drops the row of a removed one`() {
        val (created, request, artists) = createEventWithImportedLineup("added-removed")
        val added = createArtist(ArtistRequestFixtures.create(name = "Lineup Newcomer")).id

        putEvent(
            created.id,
            request.copy(
                artists =
                    listOf(
                        EventArtistRequest(artistId = artists[0], role = ArtistRole.HEADLINER, billingOrder = 0),
                        EventArtistRequest(artistId = added, role = ArtistRole.SUPPORT, billingOrder = 1)
                    )
            )
        )

        val rows = lineupRows(created.id)
        rows.keys shouldBe setOf(artists[0], added)
        rows.getValue(artists[0]).derived shouldBe importedColumns(0)
        rows.getValue(added).derived shouldBe DerivedColumns(titleDerived = false, setStart = null, setEnd = null)
    }

    @Test
    fun `PUT event that changes an artist's role and billing order keeps its title_derived and set times`() {
        val (created, request, artists) = createEventWithImportedLineup("reordered")

        putEvent(
            created.id,
            request.copy(
                artists =
                    listOf(
                        EventArtistRequest(artistId = artists[1], role = ArtistRole.HEADLINER, billingOrder = 0),
                        EventArtistRequest(artistId = artists[0], role = ArtistRole.SUPPORT, billingOrder = 1)
                    )
            )
        )

        val rows = lineupRows(created.id)
        rows.getValue(artists[1]).role shouldBe ArtistRole.HEADLINER.name
        rows.getValue(artists[1]).billingOrder shouldBe 0
        rows.getValue(artists[1]).derived shouldBe importedColumns(1)
        rows.getValue(artists[0]).role shouldBe ArtistRole.SUPPORT.name
        rows.getValue(artists[0]).billingOrder shouldBe 1
        rows.getValue(artists[0]).derived shouldBe importedColumns(0)
    }

    @Test
    fun `PUT event sets the pick, a PUT without the field keeps it, and an explicit null clears it`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Featured Venue"))
        val request = EventRequestFixtures.create(venueId = venue.id, sourceId = "test:put-featured")
        val created = createEvent(request)
        val until = Instant.parse("2026-12-31T22:00:00Z")

        putEvent(created.id, requestJson(request).put("featuredUntil", until.toString())).also {
            it.featuredUntil shouldBe until
            // A pick is no correction of the source's data, so it pins nothing.
            it.pinnedFields shouldBe emptyList()
        }
        storedFeaturedUntil(created.id) shouldBe until

        // The admin form (#3013) can save an event without knowing of the pick.
        putEvent(created.id, requestJson(request).apply { remove("featuredUntil") }.put("title", "Edited")).featuredUntil shouldBe until
        storedFeaturedUntil(created.id) shouldBe until

        putEvent(created.id, requestJson(request).putNull("featuredUntil")).featuredUntil shouldBe null
        storedFeaturedUntil(created.id) shouldBe null
    }

    /** The request as the wire carries it, so a test can tell a missing field from an explicit null. */
    private fun requestJson(request: EventRequest): ObjectNode = jsonMapper.valueToTree(request)

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

    private fun storedFeaturedUntil(id: Long): Instant? =
        runBlocking {
            databaseClient
                .sql("SELECT featured_until FROM events.event WHERE id = $id")
                .fetch()
                .one()
                .awaitSingle()["featured_until"]
                ?.let { (it as OffsetDateTime).toInstant() }
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

    // #347: the New event form asks which events a venue already holds on a date before it saves.
    @Test
    fun `GET events filters by venue, by date, by both and by neither`() {
        val lido = createVenue(VenueRequestFixtures.create(name = "Filter Lido"))
        val astra = createVenue(VenueRequestFixtures.create(name = "Filter Astra"))
        val friday = LocalDate.of(2026, 10, 9)
        val saturday = LocalDate.of(2026, 10, 10)
        val lidoFriday = createEvent(EventRequestFixtures.create(venueId = lido.id, sourceId = "test:lido-fri", eventDate = friday))
        val lidoSaturday = createEvent(EventRequestFixtures.create(venueId = lido.id, sourceId = "test:lido-sat", eventDate = saturday))
        val astraSaturday = createEvent(EventRequestFixtures.create(venueId = astra.id, sourceId = "test:astra-sat", eventDate = saturday))

        fun idsWhere(query: String): Pair<List<Long>, Long> {
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

        assertSoftly {
            idsWhere("venueId=${lido.id}") shouldBe (listOf(lidoFriday.id, lidoSaturday.id) to 2L)
            idsWhere("date=2026-10-10").first shouldContainExactlyInAnyOrder listOf(lidoSaturday.id, astraSaturday.id)
            idsWhere("date=2026-10-10").second shouldBe 2L
            idsWhere("venueId=${lido.id}&date=2026-10-10") shouldBe (listOf(lidoSaturday.id) to 1L)
            idsWhere("size=50").first shouldContainExactlyInAnyOrder listOf(lidoFriday.id, lidoSaturday.id, astraSaturday.id)
            idsWhere("venueId=${lido.id}&date=2026-10-11") shouldBe (emptyList<Long>() to 0L)
        }
    }

    @Test
    fun `GET events for an unknown venue returns an empty page, not 404`() {
        val venue = createVenue(VenueRequestFixtures.create(name = "Filter Known Venue"))
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

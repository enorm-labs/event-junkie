package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRequestFixtures
import de.norm.events.artist.ArtistResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** `PUT /api/admin/events/{id}/timetable`: hand-entered set times, rolled past midnight and pinned. */
class EventTimetableTest : BaseControllerTest() {
    @Test
    fun `PUT sets the times of two floors across midnight, clears an act left out, and pins the lineup`() {
        val (event, acts) = createNight("test:timetable-night", "Opener", "Closer", "Lower Floor", "Untimed")
        val (opener, closer, lower) = acts
        val untimed = acts[3]
        put(event.id, """{"slots":[{"artistId":$untimed,"start":"22:00"}]}""").expectStatus().isOk

        put(
            event.id,
            """
            {"slots":[
              {"artistId":$opener,"start":"23:00","end":"02:00"},
              {"artistId":$closer,"start":"07:00"},
              {"artistId":$lower,"start":"23:00","end":"08:00"}
            ]}
            """.trimIndent()
        ).expectStatus().isOk

        val stored = getEvent(event.id)
        val times = stored.artists.associate { it.artistId to (it.setStart to it.setEnd) }
        times[opener] shouldBe (Instant.parse("2026-11-07T22:00:00Z") to Instant.parse("2026-11-08T01:00:00Z"))
        times[closer] shouldBe (Instant.parse("2026-11-08T06:00:00Z") to null)
        times[lower] shouldBe (Instant.parse("2026-11-07T22:00:00Z") to Instant.parse("2026-11-08T07:00:00Z"))
        times[untimed] shouldBe (null to null)
        stored.pinnedFields shouldBe listOf("lineup")
    }

    @Test
    fun `PUT that changes no time pins nothing`() {
        val (event, _) = createNight("test:timetable-unchanged", "Only Act")

        put(event.id, """{"slots":[]}""").expectStatus().isOk

        getEvent(event.id).pinnedFields shouldBe emptyList()
    }

    @Test
    fun `PUT refuses an act off the lineup, an act twice and a slot without a start`() {
        val (event, acts) = createNight("test:timetable-refused", "Billed")
        val stranger = createArtist("Not Billed")

        put(event.id, """{"slots":[{"artistId":$stranger,"start":"23:00"}]}""").expectStatus().isBadRequest
        put(event.id, """{"slots":[{"artistId":${acts[0]},"start":"23:00"},{"artistId":${acts[0]},"start":"01:00"}]}""")
            .expectStatus()
            .isBadRequest
        put(event.id, """{"slots":[{"artistId":${acts[0]}}]}""").expectStatus().isBadRequest

        getEvent(event.id).also {
            it.pinnedFields shouldBe emptyList()
            it.artists.single().setStart shouldBe null
        }
    }

    @Test
    fun `PUT for an unknown event is a 404`() {
        put(999_999, """{"slots":[]}""").expectStatus().isNotFound
    }

    /** A Saturday night in November (CET, UTC+1) that starts at 23:00, billing [names] in order. */
    private fun createNight(
        sourceId: String,
        vararg names: String
    ): Pair<EventResponse, List<Long>> {
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
        val acts = names.map { createArtist("$it $sourceId") }
        val event =
            webTestClient
                .post()
                .uri("/api/admin/events")
                .bodyValue(
                    EventRequestFixtures.create(
                        venueId = venue.id,
                        sourceId = sourceId,
                        eventType = EventType.PARTY,
                        eventDate = LocalDate.of(2026, 11, 7),
                        startTime = LocalTime.of(23, 0),
                        artists = acts.mapIndexed { i, id -> EventArtistRequest(artistId = id, role = ArtistRole.DJ, billingOrder = i) }
                    )
                ).exchange()
                .expectStatus()
                .isCreated
                .expectBody<EventResponse>()
                .returnResult()
                .responseBody!!
        return event to acts
    }

    private fun createArtist(name: String): Long =
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
            .id

    private fun put(
        id: Long,
        body: String
    ): WebTestClient.ResponseSpec =
        webTestClient
            .put()
            .uri("/api/admin/events/$id/timetable")
            .bodyValue(body)
            .header("Content-Type", "application/json")
            .exchange()

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

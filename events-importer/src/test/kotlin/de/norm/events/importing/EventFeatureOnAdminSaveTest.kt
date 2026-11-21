package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventRequest
import de.norm.events.event.EventRequestFixtures
import de.norm.events.event.EventResponse
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

/** An admin create or edit reads the event features off the text, as an import does (#5334). */
class EventFeatureOnAdminSaveTest : BaseControllerTest() {
    @Autowired
    private lateinit var featureRepository: EventFeatureRepository

    @Autowired
    private lateinit var qualityFlagRepository: EventQualityFlagRepository

    @Test
    fun `a created event stores the features its title states`() {
        val venue = createVenue("Feature Create Venue")

        val created = create(request(venue.id, "manual:feature-create", "How many Mics?!? (Open Mic with DJ BOOM BAP)"))

        featuresOf(created.id) shouldBe listOf("open-stage")
    }

    @Test
    fun `an edit sets the features the new text states and clears the ones it dropped`() {
        val venue = createVenue("Feature Edit Venue")
        val event = create(request(venue.id, "manual:feature-edit", "Quiet night"))
        featuresOf(event.id) shouldBe emptyList()

        update(event.id, request(venue.id, "manual:feature-edit", "Open Stage night"))
        featuresOf(event.id) shouldBe listOf("open-stage")

        update(event.id, request(venue.id, "manual:feature-edit", "Quiet night again"))
        featuresOf(event.id) shouldBe emptyList()
    }

    @Test
    fun `an event that pins its features keeps them through an edit`() {
        val venue = createVenue("Feature Pinned Venue")
        val event = create(request(venue.id, "manual:feature-pinned", "Quiet night"))
        webTestClient
            .put()
            .uri("/api/admin/events/${event.id}/features")
            .bodyValue(EventFeaturesRequest(listOf("queer")))
            .exchange()
            .expectStatus()
            .isOk

        update(event.id, request(venue.id, "manual:feature-pinned", "Open Mic night"))

        featuresOf(event.id) shouldBe listOf("queer")
    }

    @Test
    fun `an uncertain cue becomes one worklist flag, however often the event is saved`() {
        val venue = createVenue("Feature Cue Venue")
        val event = create(request(venue.id, "manual:feature-cue", "Jam Night"))

        update(event.id, request(venue.id, "manual:feature-cue", "Jam Night"))
        update(event.id, request(venue.id, "manual:feature-cue", "Jam Night"))

        featuresOf(event.id) shouldBe emptyList()
        uncertainOf(event.id) shouldBe listOf("open-stage")
    }

    private fun request(
        venueId: Long,
        sourceId: String,
        title: String
    ): EventRequest = EventRequestFixtures.create(venueId = venueId, title = title, sourceId = sourceId, eventDate = LocalDate.of(2026, 12, 6))

    private fun create(request: EventRequest): EventResponse =
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

    private fun update(
        id: Long,
        request: EventRequest
    ): EventResponse =
        webTestClient
            .put()
            .uri("/api/admin/events/$id")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

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

    private fun featuresOf(eventId: Long): List<String> = runBlocking { featureRepository.findByEventIds(listOf(eventId)).map { it.feature } }

    private fun uncertainOf(eventId: Long): List<String> =
        runBlocking {
            qualityFlagRepository
                .findByEventIds(listOf(eventId))
                .filter { it.kind == QualityFlagKind.UNCERTAIN_PARTY_FEATURE }
                .map { it.value.substringBefore(':') }
        }
}

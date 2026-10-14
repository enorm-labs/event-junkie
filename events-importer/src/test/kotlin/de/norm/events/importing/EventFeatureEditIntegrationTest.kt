package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventRepository
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ProblemDetail
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

/**
 * `PUT /api/admin/events/{id}/features` against PostgreSQL and the importer merge (#3066): it pins the
 * features, the import keeps them, and an unpinned event takes the rules' features again.
 */
class EventFeatureEditIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventUpsertService: EventUpsertService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var qualityFlagRepository: EventQualityFlagRepository

    private var venueId: Long = 0
    private var eventSourceId: Long = 0
    private val venueSlug = "so36"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "SO36", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "SO36",
                                slug = venueSlug,
                                url = "https://www.so36.com/",
                                sourceType = "SO36",
                                enabled = true
                            )
                        ).id
                )
        }
    }

    /** The night as the venue publishes it: two clear features, one uncertain cue and one name the gate refuses. */
    private val published =
        ScrapedEvent(
            title = "Gayhane",
            subtitle = "Queer party until open end. A night with FLINTA* DJs.",
            eventType = "PARTY",
            eventDate = date,
            sourceId = "so36:gayhane",
            sourceUrl = "https://www.so36.com/gayhane",
            artists = listOf(ScrapedArtist("TBA"))
        )

    private suspend fun import() = eventUpsertService.upsertAndCleanup(listOf(published), venueId, venueSlug, eventSourceId)

    private suspend fun storedId(): Long =
        requireNotNull(
            eventRepository
                .findBySourceIdIn(listOf(published.sourceId))
                .toList()
                .single()
                .id
        )

    @Test
    fun `the route sets, replaces and empties the features, an import keeps them, and an unpinned event takes the rules' features again`(): Unit =
        runBlocking {
            import()
            val id = storedId()
            val detected = get(id)
            detected.slugs() shouldBe listOf("queer", "open-end")
            detected.uncertain shouldBe listOf(EventFeatureEntry("flinta-only", "…party until open end. A night with FLINTA* DJs."))
            detected.pinned shouldBe false

            val set = put(id, listOf("queer", "flinta-only", "queer"))
            set.features shouldBe
                listOf(EventFeatureEntry("flinta-only", EventFeatureService.SET_BY_HAND), EventFeatureEntry("queer", "Queer party"))
            set.pinned shouldBe true
            pinnedFields(id) shouldContain "features"

            put(id, listOf("day-party")).slugs() shouldBe listOf("day-party")
            import()
            get(id).slugs() shouldBe listOf("day-party")

            put(id, emptyList()).features.shouldBeEmpty()
            import()
            val kept = get(id)
            kept.features.shouldBeEmpty()
            kept.uncertain.shouldBeEmpty()

            unpin(id)
            pinnedFields(id) shouldNotContain "features"
            get(id).pinned shouldBe false
            import()
            val recomputed = get(id)
            recomputed.slugs() shouldBe listOf("queer", "open-end")
            recomputed.uncertain.map { it.feature } shouldBe listOf("flinta-only")
        }

    @Test
    fun `setting the features clears the event's uncertain cues and keeps its other flags`(): Unit =
        runBlocking {
            import()
            val id = storedId()
            qualityFlagRepository.findByEventIds(listOf(id)).map { it.kind }.toSet() shouldBe
                setOf(QualityFlagKind.NON_ARTIST_NAME, QualityFlagKind.UNCERTAIN_PARTY_FEATURE)

            put(id, listOf("queer", "open-end")).uncertain.shouldBeEmpty()

            qualityFlagRepository.findByEventIds(listOf(id)) shouldBe listOf(EventQualityFlag(id, QualityFlagKind.NON_ARTIST_NAME, "TBA"))
        }

    @Test
    fun `an unknown slug is a 400 naming the valid ones and changes nothing, and an unknown event is a 404`(): Unit =
        runBlocking {
            import()
            val id = storedId()

            val problem =
                webTestClient
                    .put()
                    .uri("/api/admin/events/$id/features")
                    .bodyValue(EventFeaturesRequest(listOf("queer", "darkroom")))
                    .exchange()
                    .expectStatus()
                    .isBadRequest
                    .expectBody<ProblemDetail>()
                    .returnResult()
                    .responseBody!!
            problem.detail!! shouldContain "Unknown party feature 'darkroom'"
            problem.detail!! shouldContain "flinta-only, queer, sex-positive"
            get(id).pinned shouldBe false
            get(id).slugs() shouldBe listOf("queer", "open-end")

            webTestClient
                .put()
                .uri("/api/admin/events/999999/features")
                .bodyValue(EventFeaturesRequest(listOf("queer")))
                .exchange()
                .expectStatus()
                .isNotFound
            webTestClient
                .get()
                .uri("/api/admin/events/999999/features")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    private fun EventFeaturesResponse.slugs() = features.map { it.feature }

    private suspend fun pinnedFields(id: Long): List<String> = requireNotNull(eventRepository.findById(id)).pinnedFields

    private fun get(id: Long): EventFeaturesResponse =
        webTestClient
            .get()
            .uri("/api/admin/events/$id/features")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventFeaturesResponse>()
            .returnResult()
            .responseBody!!

    private fun put(
        id: Long,
        features: List<String>
    ): EventFeaturesResponse =
        webTestClient
            .put()
            .uri("/api/admin/events/$id/features")
            .bodyValue(EventFeaturesRequest(features))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventFeaturesResponse>()
            .returnResult()
            .responseBody!!

    private fun unpin(id: Long) {
        webTestClient
            .delete()
            .uri("/api/admin/events/$id/pins/features")
            .exchange()
            .expectStatus()
            .isNoContent
    }
}

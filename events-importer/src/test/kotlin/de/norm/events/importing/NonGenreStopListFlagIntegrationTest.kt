package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.event.EventRepository
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime

/**
 * #5030 through the importer merge against a real PostgreSQL: a genre word on the stop list is dropped without a
 * `NON_GENRE_TOKEN` flag, and only a token the list does not name reaches the worklist.
 */
class NonGenreStopListFlagIntegrationTest : BaseControllerTest() {
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
    private val venueSlug = "bka-theater"

    @BeforeEach
    fun setUpFixtures(): Unit =
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "BKA Theater", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "BKA Theater",
                                slug = venueSlug,
                                url = "https://www.bka-theater.de/spielplan/",
                                sourceType = "BKA_THEATER",
                                enabled = true
                            )
                        ).id
                )
        }

    private fun performance(genre: String) =
        ScrapedEvent(
            title = "Impro-Abend",
            eventType = "OTHER",
            eventDate = LocalDate.now(BERLIN).plusDays(7),
            startTime = LocalTime.of(20, 0),
            sourceId = "bka:impro-abend",
            sourceUrl = "https://www.bka-theater.de/spielplan/1",
            genre = genre
        )

    @Test
    fun `a stop-listed genre word is not flagged, and an unknown token beside it is`(): Unit =
        runBlocking {
            val event = performance("Neuköllnical, Improvisationstheater, Lange Nacht der Museen")

            eventUpsertService.upsertAndCleanup(listOf(event), venueId, venueSlug, eventSourceId)

            val id =
                requireNotNull(
                    eventRepository
                        .findBySourceIdIn(listOf(event.sourceId))
                        .toList()
                        .single()
                        .id
                )
            qualityFlagRepository.findByEventIds(listOf(id)) shouldBe
                listOf(EventQualityFlag(id, QualityFlagKind.NON_GENRE_TOKEN, "Lange Nacht der Museen"))
        }
}

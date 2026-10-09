package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventRepository
import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.collapseExhibitionRuns
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/**
 * An exhibition run imported on two consecutive days, against a real PostgreSQL (Testcontainers). The second listing no longer
 * carries the first day, and the stored opening, the slug and the row stay as the first import wrote them (#2940).
 */
class ExhibitionRunOpeningIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventUpsertService: EventUpsertService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    private var venueId: Long = 0
    private var eventSourceId: Long = 0

    private val venueSlug = "berghain-panorama-bar"
    private val runId = "berghain:exhibition-a-shroud-woven-of-solar-threads"
    private val today: LocalDate = LocalDate.now(BERLIN)
    private val closing: LocalDate = today.plusDays(9)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            val venue = venueRepository.save(VenueEntity(name = "Berghain / Panorama Bar", slug = venueSlug))
            venueId = requireNotNull(venue.id)
            val source =
                eventSourceRepository.save(
                    EventSourceEntity(
                        venueId = venueId,
                        name = "Berghain",
                        slug = "berghain",
                        url = "https://www.berghain.berlin/de/program/",
                        sourceType = "BERGHAIN",
                        enabled = true
                    )
                )
            eventSourceId = requireNotNull(source.id)
        }
    }

    /** The overview's open days from [firstListed], folded into one run as the importer folds them. */
    private fun listing(firstListed: LocalDate): List<ScrapedEvent> =
        firstListed
            .datesUntil(closing.plusDays(1))
            .toList()
            .map { day ->
                ScrapedEvent(
                    title = "A Shroud Woven of Solar Threads",
                    eventType = EventType.EXHIBITION.name,
                    eventDate = day,
                    sourceUrl = "https://www.berghain.berlin/de/event/$day/",
                    sourceId = "berghain:$day"
                )
            }.collapseExhibitionRuns { runId }

    private suspend fun import(firstListed: LocalDate) =
        eventUpsertService.upsertAndCleanup(listing(firstListed), venueId, venueSlug, eventSourceId, staleCleanup = StaleCleanup.OPEN_ENDED)

    @Test
    fun `a run imported on two consecutive days keeps its first opening and slug`() {
        runBlocking {
            // Yesterday's import listed yesterday as the first day. Today's listing has dropped it.
            import(firstListed = today.minusDays(1)).inserted shouldBe 1
            val first = eventRepository.findAll().toList().single()

            val outcome = import(firstListed = today)

            outcome.inserted shouldBe 0
            val after = eventRepository.findAll().toList().single()
            after.id shouldBe first.id
            after.eventDate shouldBe today.minusDays(1)
            after.endDate shouldBe closing
            after.slug shouldBe first.slug
        }
    }
}

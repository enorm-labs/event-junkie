package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventRepository
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
 * The spoken language through the upsert into a real PostgreSQL (#2523): the `text[]` column, its
 * checks, and change detection over a list.
 */
class SpokenLanguageIntegrationTest : BaseControllerTest() {
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
    private val venueSlug = "the-wall-comedy-club"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "The Wall", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "The Wall",
                                slug = venueSlug,
                                url = "https://thewallcomedy.de/",
                                sourceType = "THE_WALL",
                                enabled = true
                            )
                        ).id
                )
        }
    }

    private fun comedy(
        slug: String,
        title: String,
        type: String = "COMEDY"
    ) = ScrapedEvent(
        title = title,
        eventDate = date,
        sourceId = "the_wall:$slug",
        sourceUrl = "https://thewallcomedy.de/$slug",
        eventType = type
    )

    private suspend fun upsert(vararg events: ScrapedEvent) = eventUpsertService.upsertAndCleanup(events.toList(), venueId, venueSlug, eventSourceId)

    @Test
    fun `stores the languages as an array, and a re-import of the same show changes nothing`() {
        runBlocking {
            val shows =
                arrayOf(
                    comedy("after-party", "After Party Comedy: Stand-Up in English Sundays 6pm at The Wall"),
                    comedy("appartheit", "IT’S CALLED APPARTHEIT – German & English Stand-up Comedy"),
                    comedy("film", "Paris, Texas (OmU)", type = "SCREENING"),
                    comedy("unsaid", "Mixed Show")
                )
            upsert(*shows).inserted shouldBe 4

            val stored = eventRepository.findAll().toList().associateBy { it.sourceId }
            stored.getValue("the_wall:after-party").spokenLanguages shouldBe listOf("en")
            stored.getValue("the_wall:appartheit").spokenLanguages shouldBe listOf("de", "en")
            stored.getValue("the_wall:film").subtitleLanguage shouldBe "de"
            stored.getValue("the_wall:unsaid").spokenLanguages shouldBe null

            val again = upsert(*shows)
            again.updated shouldBe 0
            again.skipped shouldBe 4
        }
    }
}

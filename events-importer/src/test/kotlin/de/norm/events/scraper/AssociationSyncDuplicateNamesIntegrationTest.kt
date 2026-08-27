package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/**
 * Integration test for one event naming the same artist or promoter twice, against a real
 * PostgreSQL (Testcontainers).
 *
 * **The constraint is the point, so a mock cannot stand in for it.** `event_artist` and
 * `event_promoter` each carry `UNIQUE (event_id, <other>_id)`, and the defect these tests cover was
 * two identical rows reaching one `saveAll` batch. Asserted against a repository double the batch
 * looks merely redundant; asserted against Postgres it is what aborted the transaction and lost a
 * whole source's run (#798).
 *
 * Both cases arise from a venue's own page rather than from anything exotic: a lineup that bills one
 * act twice, and two spellings that `canonicalArtistName` folds together.
 */
class AssociationSyncDuplicateNamesIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var eventArtistRepository: EventArtistRepository

    @Autowired
    private lateinit var eventPromoterRepository: EventPromoterRepository

    private suspend fun persistEvent(sourceId: String): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Test Venue", slug = "test-venue-$sourceId"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = "Test Event",
                slug = "test-event-$sourceId",
                eventDate = LocalDate.of(2026, 9, 1),
                sourceId = sourceId
            )
        )
    }

    private fun scraped(
        sourceId: String,
        artists: List<ScrapedArtist> = emptyList(),
        promoters: List<String> = emptyList()
    ) = ScrapedEvent(
        title = "Test Event",
        eventDate = LocalDate.of(2026, 9, 1),
        sourceId = sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "CONCERT",
        status = "SCHEDULED",
        artists = artists,
        promoters = promoters
    )

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `a promoter named twice on one event yields one association`() {
        runBlocking {
            val sourceId = "dup-promoter:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, promoters = listOf("Wild Nights", "Wild Nights")))
            )

            eventPromoterRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().size shouldBe 1
        }
    }

    @Test
    fun `two artist names that canonicalise together yield one association`() {
        runBlocking {
            val sourceId = "dup-artist:1"
            val event = persistEvent(sourceId)

            // Same act, two spellings. They slugify to one artist, so the second must not produce a
            // second row for the same pair.
            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(
                        sourceId,
                        artists = listOf(ScrapedArtist(name = "The Beuys"), ScrapedArtist(name = "the beuys"))
                    )
                )
            )

            eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().size shouldBe 1
        }
    }

    @Test
    fun `the first mention keeps its billing when an act is listed twice`() {
        runBlocking {
            val sourceId = "dup-artist:2"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(
                        sourceId,
                        artists =
                            listOf(
                                ScrapedArtist(name = "Anna Mateur", role = "HEADLINER"),
                                ScrapedArtist(name = "anna mateur", role = "SUPPORT")
                            )
                    )
                )
            )

            // First mention wins, which is the readable rule: a page that bills an act at the top
            // and repeats it further down means the top billing.
            val associations = eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList()
            associations.size shouldBe 1
            associations.first().role shouldBe "HEADLINER"
            associations.first().billingOrder shouldBe 0
        }
    }
}

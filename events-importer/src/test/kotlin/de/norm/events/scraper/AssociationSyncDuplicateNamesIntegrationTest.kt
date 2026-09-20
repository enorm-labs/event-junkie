package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.promoter.PromoterRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
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

    @Autowired
    private lateinit var promoterRepository: PromoterRepository

    @Autowired
    private lateinit var artistRepository: ArtistRepository

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
        promoters: List<String> = emptyList(),
        promoterWebsites: Map<String, String> = emptyMap()
    ) = ScrapedEvent(
        title = "Test Event",
        eventDate = LocalDate.of(2026, 9, 1),
        sourceId = sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "CONCERT",
        status = "SCHEDULED",
        artists = artists,
        promoters = promoters,
        promoterWebsites = promoterWebsites
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

    // #1319: the venue's link fills an empty website and never replaces a reviewed one.
    @Test
    fun `a promoter credit with a link fills the website of a row that has none`() {
        runBlocking {
            val sourceId = "promoter-site:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, promoters = listOf("Wild Nights GmbH"), promoterWebsites = mapOf("Wild Nights GmbH" to "https://wild.example")))
            )
            promoterRepository.findBySlug("wild-nights")?.websiteUrl shouldBe "https://wild.example"

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, promoters = listOf("Wild Nights"), promoterWebsites = mapOf("Wild Nights" to "https://shop.example")))
            )
            promoterRepository.findBySlug("wild-nights")?.websiteUrl shouldBe "https://wild.example"
        }
    }

    // #1362: Lido linked a credit to the event page itself.
    @Test
    fun `a credit link on the venue's own host does not become the promoter's website`() {
        runBlocking {
            val sourceId = "promoter-self-link:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(
                        sourceId,
                        promoters = listOf("Touring Tunes"),
                        promoterWebsites =
                            mapOf("Touring Tunes" to "https://www.example.com/events/latexfauna")
                    )
                )
            )

            promoterRepository.findBySlug("touring-tunes")?.websiteUrl shouldBe null
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

    // #1553: a name that slugs to nothing would take the empty slug, and the next one would collide.
    @Test
    fun `an artist whose name slugs to nothing is dropped, and the rest of the lineup is kept`() {
        runBlocking {
            val sourceId = "slugless-artist:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, artists = listOf(ScrapedArtist(name = "-"), ScrapedArtist(name = "Real Band"))))
            )

            artistRepository.findBySlug("") shouldBe null
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

    // The flag is written on insert and rewritten on resync, which is what backfills rows stored
    // before the column existed once their source is imported again (#1145).
    @Test
    fun `title-derived provenance is stored and updated on resync`() {
        runBlocking {
            val sourceId = "title-derived:1"
            val event = persistEvent(sourceId)
            val eventId = requireNotNull(event.id)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, artists = listOf(ScrapedArtist(name = "Night Name"))))
            )
            eventArtistRepository.findByEventIdIn(listOf(eventId)).first().titleDerived shouldBe false

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, artists = listOf(ScrapedArtist(name = "Night Name", titleDerived = true))))
            )
            eventArtistRepository.findByEventIdIn(listOf(eventId)).first().titleDerived shouldBe true
        }
    }
}

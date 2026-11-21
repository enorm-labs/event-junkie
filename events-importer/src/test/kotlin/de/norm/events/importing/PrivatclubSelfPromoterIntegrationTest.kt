package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.privatclub.PrivatclubOverviewPageScraper
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A Privatclub band named as its own `Örtlicher Veranstalter`, from the scraper through the association merge (#5372).
 *
 * The scraper test proves the promoter is dropped. Only the merge proves the act is billed: the #1841 hold-back in
 * `ArtistAssociationSync.billedArtists` withholds a title-derived act that the event also credits as its promoter.
 */
class PrivatclubSelfPromoterIntegrationTest : BaseControllerTest() {
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
    private lateinit var artistRepository: ArtistRepository

    private val baseUrl = "https://privatclub-berlin.de/"

    /** Event 15068, trimmed to the fields the scraper reads. */
    private val card =
        """
        <div class="event_wrapper skewed">
            <a href="https://privatclub-berlin.de/event/mary-and-the-sharks/" class="event_header">
                <span class="datum_part1">Mo. 8.</span> <span class="datum_part2">März</span>
                <div class="zeile_typ"><div class="event_typ">Konzert</div></div>
                <span class="titel">Mary And The Sharks</span>
            </a>
            <div class="event_detail">
                <div class="zeile content">
                    <div class="veranstaltertext tk-proxima-nova">Örtlicher Veranstalter: Mary And The Sharks</div>
                </div>
            </div>
        </div>
        """.trimIndent()

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `a band named as its own promoter is billed and linked to no promoter`() {
        runBlocking {
            val scraped = PrivatclubOverviewPageScraper().scrape(Jsoup.parse(card, baseUrl), baseUrl).single()
            val event = persistEvent(scraped)

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped))

            billedSlugs(event) shouldContainExactly listOf("mary-and-the-sharks")
            eventPromoterRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().shouldBeEmpty()
        }
    }

    // The control: the scraper's output with the promoter put back is what billed nobody in production.
    @Test
    fun `the same event crediting the band as promoter bills nobody`() {
        runBlocking {
            val scraped = PrivatclubOverviewPageScraper().scrape(Jsoup.parse(card, baseUrl), baseUrl).single()
            val event = persistEvent(scraped)

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped.copy(promoters = listOf("Mary And The Sharks"))))

            billedSlugs(event).shouldBeEmpty()
        }
    }

    private suspend fun persistEvent(scraped: ScrapedEvent): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Privatclub", slug = "privatclub"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = scraped.title,
                slug = "mary-and-the-sharks",
                eventDate = scraped.eventDate,
                sourceId = scraped.sourceId
            )
        )
    }

    private suspend fun billedSlugs(event: EventEntity): List<String> =
        eventArtistRepository
            .findByEventIdIn(listOf(requireNotNull(event.id)))
            .toList()
            .map { requireNotNull(artistRepository.findById(it.artistId)).slug }
}

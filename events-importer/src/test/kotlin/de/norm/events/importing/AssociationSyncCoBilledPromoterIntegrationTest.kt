package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.promoter.PromoterEntity
import de.norm.events.promoter.PromoterRepository
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/** A credit of two promoters joined by "&", against a real database (#4323). */
class AssociationSyncCoBilledPromoterIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var eventPromoterRepository: EventPromoterRepository

    @Autowired
    private lateinit var promoterRepository: PromoterRepository

    // Block bodies: an expression body that returns non-Unit makes JUnit skip the test.
    @Test
    fun `a credit with one stored side bills each promoter and stores no fused row`() {
        runBlocking {
            promoterRepository.save(PromoterEntity(name = "Milchmädchen Musikkultur", slug = "milchmadchen-musikkultur"))
            val event = persistEvent("cobilled:1")

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(event.sourceId, "Milchmädchen Musikkultur GmbH & Dasmituns"))
            )

            credits(event) shouldContainExactlyInAnyOrder listOf("milchmadchen-musikkultur", "dasmituns")
            promoterRepository.findBySlug("milchmadchen-musikkultur-gmbh-dasmituns").shouldBeNull()
        }
    }

    @Test
    fun `a credit with no stored side stays one promoter`() {
        runBlocking {
            val event = persistEvent("cobilled:2")

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event.sourceId, "Kollektiv Kunst & Krawall")))

            credits(event) shouldContainExactlyInAnyOrder listOf("kollektiv-kunst-krawall")
        }
    }

    @Test
    fun `an enrichment source's co-billing fills the promoters split`() {
        runBlocking {
            promoterRepository.save(PromoterEntity(name = "Crunch Tapes", slug = "crunch-tapes"))
            val event = persistEvent("cobilled:3")

            associationSyncService.fillEmptyAssociations(listOf(event to scraped(event.sourceId, "Crunch Tapes & Positive Transmitter")), emptySet())

            credits(event) shouldContainExactlyInAnyOrder listOf("crunch-tapes", "positive-transmitter")
        }
    }

    private suspend fun credits(event: EventEntity): List<String> {
        val ids = eventPromoterRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().map { it.promoterId }
        return promoterRepository.findAllById(ids).toList().map { it.slug }
    }

    private suspend fun persistEvent(sourceId: String): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Test Venue", slug = "test-venue-${sourceId.replace(':', '-')}"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = "Test Event",
                slug = "test-event-${sourceId.replace(':', '-')}",
                eventDate = LocalDate.of(2026, 12, 31),
                sourceId = sourceId
            )
        )
    }

    private fun scraped(
        sourceId: String,
        promoter: String
    ) = ScrapedEvent(
        title = "Test Event",
        eventDate = LocalDate.of(2026, 12, 31),
        sourceId = sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "CONCERT",
        status = "SCHEDULED",
        promoters = listOf(promoter)
    )
}

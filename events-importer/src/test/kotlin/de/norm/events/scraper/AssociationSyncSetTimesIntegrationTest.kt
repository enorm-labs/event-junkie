package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate

/**
 * Integration test for set times on `event_artist`, against a real PostgreSQL (Testcontainers).
 *
 * A venue publishes its running order days after the lineup (#2002), so the times have to reach a
 * row that already exists. The round trip through `timestamptz` is the other half: a value that
 * came back unequal to what the scrape produced would rewrite every row on every run.
 */
class AssociationSyncSetTimesIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var eventArtistRepository: EventArtistRepository

    private val start = Instant.parse("2026-09-26T21:59:00Z")
    private val end = Instant.parse("2026-09-27T02:30:00Z")

    private suspend fun persistEvent(sourceId: String): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Test Venue", slug = "test-venue-$sourceId"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = "Klubnacht",
                slug = "klubnacht-$sourceId",
                eventDate = LocalDate.of(2026, 9, 26),
                sourceId = sourceId
            )
        )
    }

    private fun scraped(
        sourceId: String,
        artist: ScrapedArtist
    ) = ScrapedEvent(
        title = "Klubnacht",
        eventDate = LocalDate.of(2026, 9, 26),
        sourceId = sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "PARTY",
        status = "SCHEDULED",
        artists = listOf(artist)
    )

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `a running order published after the lineup reaches the existing row`() {
        runBlocking {
            val sourceId = "set-times:1"
            val event = persistEvent(sourceId)
            val eventIds = listOf(requireNotNull(event.id))
            val act = ScrapedArtist(name = "Joline Scheffler", role = "DJ", stage = "Berghain")

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, act)))
            val before = eventArtistRepository.findByEventIdIn(eventIds).toList().single()
            before.setStart.shouldBeNull()

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, act.copy(setStart = start, setEnd = end))))
            val after = eventArtistRepository.findByEventIdIn(eventIds).toList().single()

            after.id shouldBe before.id
            after.setStart shouldBe start
            after.setEnd shouldBe end
        }
    }

    @Test
    fun `set times read back equal to the scrape, so an unchanged running order writes nothing`() {
        runBlocking {
            val sourceId = "set-times:2"
            val event = persistEvent(sourceId)
            val act = ScrapedArtist(name = "Colin Benders", role = "HEADLINER", stage = "Berghain", setStart = start, setEnd = end)
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, act)))

            val stored = eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().single()

            // The diff compares the whole row, so this equality is what keeps the next run from updating it.
            stored shouldBe act.toEventArtistEntity(stored.eventId, stored.artistId, billingOrder = 0).copy(id = stored.id)
        }
    }

    @Test
    fun `a run whose detail page yielded nothing keeps the stored set times`() {
        runBlocking {
            val sourceId = "set-times:3"
            val event = persistEvent(sourceId)
            val eventIds = listOf(requireNotNull(event.id))
            val act = ScrapedArtist(name = "Janina", role = "DJ", stage = "Tresor")
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, act.copy(setStart = start, setEnd = end))))

            // The listing names the act but not its slot (#2421).
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, act).copy(detailUnavailable = true)))
            val after = eventArtistRepository.findByEventIdIn(eventIds).toList().single()

            after.setStart shouldBe start
            after.setEnd shouldBe end
        }
    }

    @Test
    fun `a run whose detail page yielded nothing and whose listing names no act keeps the stored lineup`() {
        runBlocking {
            val sourceId = "set-times:4"
            val event = persistEvent(sourceId)
            val act = ScrapedArtist(name = "Surgeon", role = "DJ", setStart = start)
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, act)))

            val listingOnly = scraped(sourceId, act).copy(artists = emptyList(), detailUnavailable = true)
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(listingOnly))

            eventArtistRepository
                .findByEventIdIn(listOf(requireNotNull(event.id)))
                .toList()
                .single()
                .setStart shouldBe start
        }
    }
}

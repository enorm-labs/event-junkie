package de.norm.events.importing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/**
 * Two acts whose names fold to one slug, through the real resolution against PostgreSQL (#2942).
 *
 * A unit test of `artistSlugFor` proves the map. Only the merge path proves that the lookup, the insert and the
 * association all use it: one of them slugging the plain name would put the second act back on the first act's row.
 */
class AssociationSyncArtistSlugOverrideIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var eventArtistRepository: EventArtistRepository

    @Autowired
    private lateinit var artistRepository: ArtistRepository

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `Göre and Gore end on two rows, each billed by its own venue`() {
        runBlocking {
            val ufo = persistEvent("ufo:2026-10-13-i-prevail")
            associationSyncService.resolveAndSyncAssociations(listOf(ufo), listOf(scraped(ufo, "Gore")))
            val monarch = persistEvent("monarch:2026-10-09-gore")
            associationSyncService.resolveAndSyncAssociations(listOf(monarch), listOf(scraped(monarch, "GÖRE")))

            billedSlugs(ufo) shouldContainExactly listOf("gore")
            billedSlugs(monarch) shouldContainExactly listOf("goere")
            artistRepository.findBySlug("goere")?.name shouldBe "Göre"
            artistRepository.findBySlug("gore")?.name shouldBe "Gore"
        }
    }

    @Test
    fun `a billed name that reaches a row differing only by accents is logged at WARN`() {
        runBlocking {
            artistRepository.save(ArtistEntity(name = "Rosalia", slug = "rosalia"))
            val event = persistEvent("accent:1")

            val warnings =
                warningsDuring {
                    associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, "Rosalía")))
                }

            billedSlugs(event) shouldContainExactly listOf("rosalia")
            warnings shouldContainExactly
                listOf(
                    "Billed 'Rosalía' resolved to artist 'Rosalia' (rosalia): the names differ only by accents. " +
                        "A different act needs an ARTIST_SLUG_OVERRIDES entry"
                )
        }
    }

    @Test
    fun `a name differing only by case is not an accent warning`() {
        runBlocking {
            artistRepository.save(ArtistEntity(name = "Green Lung", slug = "green-lung"))
            val event = persistEvent("case:1")

            warningsDuring {
                associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, "GREEN LUNG")))
            }.shouldBeEmpty()
        }
    }

    private suspend fun warningsDuring(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(AssociationSyncService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN && "differ only by accents" in it.formattedMessage }.map { it.formattedMessage }
    }

    private suspend fun persistEvent(sourceId: String): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Venue $sourceId", slug = "venue-${sourceId.replace(':', '-')}"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = sourceId,
                slug = sourceId.replace(':', '-'),
                eventDate = LocalDate.of(2026, 10, 9),
                sourceId = sourceId
            )
        )
    }

    private fun scraped(
        event: EventEntity,
        artist: String
    ) = ScrapedEvent(
        title = event.title,
        eventDate = event.eventDate,
        sourceId = event.sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "CONCERT",
        status = "SCHEDULED",
        artists = listOf(ScrapedArtist(name = artist))
    )

    private suspend fun billedSlugs(event: EventEntity): List<String> =
        eventArtistRepository
            .findByEventIdIn(listOf(requireNotNull(event.id)))
            .toList()
            .map { requireNotNull(artistRepository.findById(it.artistId)).slug }
}

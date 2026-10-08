package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/** The sync gate against PostgreSQL: what it keeps out of an event lands in `event_quality_flag` (#320). */
class AssociationSyncQualityFlagIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var qualityFlagRepository: EventQualityFlagRepository

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var eventArtistRepository: EventArtistRepository

    @Autowired
    private lateinit var eventGenreTagRepository: EventGenreTagRepository

    @Autowired
    private lateinit var artistRepository: ArtistRepository

    private suspend fun persistEvent(sourceId: String): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Test Venue", slug = "test-venue-$sourceId"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = "Acid Night",
                slug = "acid-night-$sourceId",
                eventDate = LocalDate.of(2026, 9, 1),
                sourceId = sourceId
            )
        )
    }

    private fun scraped(
        sourceId: String,
        artists: List<String>,
        genre: String?,
        promoters: List<String> = emptyList()
    ) = ScrapedEvent(
        title = "Acid Night",
        eventDate = LocalDate.of(2026, 9, 1),
        sourceId = sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "CONCERT",
        status = "SCHEDULED",
        genre = genre,
        promoters = promoters,
        artists = artists.map { ScrapedArtist(name = it, titleDerived = it == "Acid Night") }
    )

    @Test
    fun `refused names and a genre that repeats the title are flagged, not stored`() {
        runBlocking {
            val sourceId = "gate:1"
            val event = persistEvent(sourceId)
            val eventId = requireNotNull(event.id)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, artists = listOf("Die Nerven", "TBA", "–"), genre = "ACID NIGHT"))
            )

            val linked = eventArtistRepository.findByEventIdIn(listOf(eventId)).toList().map { it.artistId }
            artistRepository.findAllById(linked).toList().map { it.name } shouldBe listOf("Die Nerven")
            eventGenreTagRepository.findByEventIdIn(listOf(eventId)).toList().shouldBeEmpty()
            qualityFlagRepository.findByEventIds(listOf(eventId)) shouldBe
                listOf(
                    EventQualityFlag(eventId, QualityFlagKind.GENRE_EQUALS_TITLE, "ACID NIGHT"),
                    EventQualityFlag(eventId, QualityFlagKind.NON_ARTIST_NAME, "TBA"),
                    EventQualityFlag(eventId, QualityFlagKind.SLUGLESS_ARTIST, "–")
                )
        }
    }

    @Test
    fun `a held-back promoter name and a genre word that names no genre are flagged, not stored`() {
        runBlocking {
            val sourceId = "gate:3"
            val event = persistEvent(sourceId)
            val eventId = requireNotNull(event.id)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, listOf("Acid Night", "Die Nerven"), genre = "Immersive Ausstellung, Techno", promoters = listOf("Acid Night")))
            )

            val linked = eventArtistRepository.findByEventIdIn(listOf(eventId)).toList().map { it.artistId }
            artistRepository.findAllById(linked).toList().map { it.name } shouldBe listOf("Die Nerven")
            qualityFlagRepository.findByEventIds(listOf(eventId)) shouldBe
                listOf(
                    EventQualityFlag(eventId, QualityFlagKind.HELD_BACK_PROMOTER_NAME, "Acid Night"),
                    EventQualityFlag(eventId, QualityFlagKind.NON_GENRE_TOKEN, "Immersive Ausstellung")
                )
        }
    }

    @Test
    fun `the next import replaces the flags, so a fixed value leaves the worklist`() {
        runBlocking {
            val sourceId = "gate:2"
            val event = persistEvent(sourceId)
            val eventId = requireNotNull(event.id)

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, listOf("TBA", "TBA"), genre = null)))
            qualityFlagRepository.findByEventIds(listOf(eventId)).size shouldBe 1

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(sourceId, listOf("Die Nerven"), genre = "Punk")))
            qualityFlagRepository.findByEventIds(listOf(eventId)).shouldBeEmpty()
        }
    }
}

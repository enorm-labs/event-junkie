package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.EventStatus
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagOrigin
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/** Derived genre tags against PostgreSQL (#3339, ADR-047). */
class GenreDerivationIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var associationSyncService: AssociationSyncService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    @Autowired
    private lateinit var eventGenreTagRepository: EventGenreTagRepository

    @Autowired
    private lateinit var genreTagRepository: GenreTagRepository

    private suspend fun persistEvent(
        sourceId: String,
        description: String? = null,
        eventType: String = "CONCERT",
        status: String = "SCHEDULED"
    ): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Venue $sourceId", slug = "venue-${sourceId.replace(':', '-')}"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = "Night $sourceId",
                slug = "night-${sourceId.replace(':', '-')}",
                eventDate = LocalDate.of(2026, 11, 1),
                sourceId = sourceId,
                description = description,
                eventType = eventType,
                status = status
            )
        )
    }

    private fun scraped(
        event: EventEntity,
        artists: List<Pair<String, String>> = emptyList(),
        genre: String? = null
    ) = ScrapedEvent(
        title = event.title,
        eventDate = event.eventDate,
        sourceId = event.sourceId,
        sourceUrl = "https://example.com/${event.sourceId}",
        eventType = event.eventType,
        status = event.status,
        genre = genre,
        description = event.description,
        artists = artists.map { (name, role) -> ScrapedArtist(name = name, role = role) }
    )

    private suspend fun sync(
        event: EventEntity,
        artists: List<Pair<String, String>> = emptyList(),
        genre: String? = null
    ) = associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, artists, genre)))

    /** The event's tags as name to origin. */
    private suspend fun tagsOf(event: EventEntity): Map<String, String> {
        val links = eventGenreTagRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList()
        val names = genreTagRepository.findAllById(links.map { it.genreTagId }).toList().associate { it.id to it.name }
        return links.associate { requireNotNull(names[it.genreTagId]) to it.origin }
    }

    @Test
    fun `an untagged concert takes the source tags its headliner carries elsewhere, and a source tag keeps none`() {
        runBlocking {
            val tagged = persistEvent("lido:1")
            sync(tagged, listOf("Die Nerven" to "HEADLINER"), genre = "Post-Punk, Noise")
            val untagged = persistEvent("so36:1", description = "Techno all night")
            sync(untagged, listOf("Die Nerven" to "HEADLINER"))

            tagsOf(tagged) shouldBe mapOf("Post-Punk" to "SOURCE", "Noise" to "SOURCE")
            tagsOf(untagged) shouldBe mapOf("Post-Punk" to "ARTIST", "Noise" to "ARTIST")
        }
    }

    @Test
    fun `a support act's tags do not tag the night`() {
        runBlocking {
            sync(persistEvent("lido:2"), listOf("Metal Band" to "HEADLINER"), genre = "Metal")
            val night = persistEvent("so36:2")
            sync(night, listOf("Folk Act" to "HEADLINER", "Metal Band" to "SUPPORT"))

            tagsOf(night) shouldBe emptyMap()
        }
    }

    @Test
    fun `a derived tag never feeds another derivation`() {
        runBlocking {
            sync(persistEvent("lido:3"), listOf("Act A" to "HEADLINER"), genre = "Jazz")
            val middle = persistEvent("so36:3")
            sync(middle, listOf("Act A" to "HEADLINER", "Act B" to "HEADLINER"))
            val last = persistEvent("bi-nuu:3")
            sync(last, listOf("Act B" to "HEADLINER"))

            tagsOf(middle) shouldBe mapOf("Jazz" to "ARTIST")
            tagsOf(last) shouldBe emptyMap()
        }
    }

    @Test
    fun `an event with no tagged act takes the genre words of its description, outside a support clause`() {
        runBlocking {
            val night = persistEvent("so36:4", description = "Ein Abend voller Techno. Support: eine Metal-Band.")
            sync(night, listOf("Unknown Act" to "HEADLINER"))

            tagsOf(night) shouldBe mapOf("Techno" to GenreTagOrigin.DESCRIPTION.name)
        }
    }

    @Test
    fun `a source tag replaces the derived ones, and a derived tag the source names becomes a source tag`() {
        runBlocking {
            val night = persistEvent("so36:5", description = "Punk und Techno")
            sync(night)
            tagsOf(night) shouldBe mapOf("Punk" to "DESCRIPTION", "Techno" to "DESCRIPTION")

            sync(night, genre = "Punk")
            tagsOf(night) shouldBe mapOf("Punk" to "SOURCE")

            sync(night)
            tagsOf(night) shouldBe mapOf("Punk" to "DESCRIPTION", "Techno" to "DESCRIPTION")
        }
    }

    @Test
    fun `an event that is not music gets no derived tag`() {
        runBlocking {
            val reading = persistEvent("so36:6", description = "Lesung über Punk", eventType = "READING")
            sync(reading)

            tagsOf(reading) shouldBe emptyMap()
        }
    }

    @Test
    fun `every status derives alike`() {
        runBlocking {
            EventStatus.entries.forEach { status ->
                val night = persistEvent("so36:status-${status.name.lowercase()}", description = "Jazz", status = status.name)
                sync(night)
                withClue(status) { tagsOf(night) shouldBe mapOf("Jazz" to "DESCRIPTION") }
            }
        }
    }
}

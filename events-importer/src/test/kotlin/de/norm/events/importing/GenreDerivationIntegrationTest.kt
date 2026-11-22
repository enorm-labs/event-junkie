package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.ArtistRole
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
import kotlinx.coroutines.reactive.awaitSingle
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

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    /** What the lookup tick stores for an act: an EXACT match and its Wikidata genres (ADR-060). */
    private suspend fun readGenres(
        name: String,
        vararg items: String,
        match: String = "EXACT"
    ) {
        databaseClient
            .sql("UPDATE events.artist SET musicbrainz_match = :match, wikidata_genres = :items WHERE name = :name")
            .bind("match", match)
            .bind("items", arrayOf(*items))
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle() shouldBe 1
    }

    private suspend fun persistEvent(
        sourceId: String,
        description: String? = null,
        eventType: String = "CONCERT",
        status: String = "SCHEDULED",
        title: String = "Night $sourceId",
        subtitle: String? = null,
        sourceType: String? = null,
        genre: String? = null
    ): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Venue $sourceId", slug = "venue-${sourceId.replace(':', '-')}"))
        val eventSourceId =
            sourceType?.let {
                eventSourceRepository
                    .save(
                        EventSourceEntity(
                            venueId = requireNotNull(venue.id),
                            name = "Source $sourceId",
                            slug = "source-${sourceId.replace(':', '-')}",
                            url = "https://example.com/",
                            sourceType = it
                        )
                    ).id
            }
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = title,
                subtitle = subtitle,
                slug = "night-${sourceId.replace(':', '-')}",
                eventDate = LocalDate.of(2026, 11, 1),
                sourceId = sourceId,
                description = description,
                eventType = eventType,
                status = status,
                eventSourceId = eventSourceId,
                genre = genre
            )
        )
    }

    private fun scraped(
        event: EventEntity,
        artists: List<Pair<String, ArtistRole>> = emptyList(),
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
        artists: List<Pair<String, ArtistRole>> = emptyList(),
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
            sync(tagged, listOf("Die Nerven" to ArtistRole.HEADLINER), genre = "Post-Punk, Noise")
            val untagged = persistEvent("so36:1", description = "Techno all night")
            sync(untagged, listOf("Die Nerven" to ArtistRole.HEADLINER))

            tagsOf(tagged) shouldBe mapOf("Post-Punk" to "SOURCE", "Noise" to "SOURCE")
            tagsOf(untagged) shouldBe mapOf("Post-Punk" to "ARTIST", "Noise" to "ARTIST")
        }
    }

    @Test
    fun `a support act's tags do not tag the night`() {
        runBlocking {
            sync(persistEvent("lido:2"), listOf("Metal Band" to ArtistRole.HEADLINER), genre = "Metal")
            val night = persistEvent("so36:2")
            sync(night, listOf("Folk Act" to ArtistRole.HEADLINER, "Metal Band" to ArtistRole.SUPPORT))

            tagsOf(night) shouldBe emptyMap()
        }
    }

    @Test
    fun `a DJ billing and a live billing do not lend each other tags, so the concert falls through to its description`() {
        runBlocking {
            sync(persistEvent("duncker:5", eventType = "PARTY"), listOf("Spy" to ArtistRole.DJ), genre = "Indie, Alternative")
            val concert = persistEvent("neue-zukunft:5", description = "Bay Area punk giants back on tour")
            sync(concert, listOf("Spy" to ArtistRole.HEADLINER))
            val party = persistEvent("duncker:6", eventType = "PARTY")
            sync(party, listOf("Spy" to ArtistRole.DJ))

            tagsOf(concert) shouldBe mapOf("Punk" to GenreTagOrigin.DESCRIPTION.name)
            tagsOf(party) shouldBe mapOf("Indie" to "ARTIST", "Alternative" to "ARTIST")
        }
    }

    @Test
    fun `an untagged concert takes its acts' mapped Wikidata genres in billing order, at most three (ADR-060)`() {
        runBlocking {
            val night = persistEvent("waldbuhne:1")
            val acts = listOf("Roland Kaiser" to ArtistRole.HEADLINER, "Peter Maffay" to ArtistRole.HEADLINER)
            sync(night, acts)
            readGenres("Roland Kaiser", "Q959583", "Q1062702", "Q9759")
            readGenres("Peter Maffay", "Q11399", "Q37073")

            sync(night, acts)

            tagsOf(night) shouldBe mapOf("Schlager" to "WIKIDATA", "Blues" to "WIKIDATA", "Rock" to "WIKIDATA")
        }
    }

    @Test
    fun `Wikidata comes last, and a support act, an unmapped item or a reset match gives nothing`() {
        runBlocking {
            val described = persistEvent("waldbuhne:2", description = "Ein Abend voller Jazz")
            sync(described, listOf("Jazz Act" to ArtistRole.HEADLINER))
            readGenres("Jazz Act", "Q11399")
            val supported = persistEvent("waldbuhne:3")
            val bill = listOf("Video Game Band" to ArtistRole.HEADLINER, "Rock Support" to ArtistRole.SUPPORT, "Reset Act" to ArtistRole.HEADLINER)
            sync(supported, bill)
            readGenres("Video Game Band", "Q1062702")
            readGenres("Rock Support", "Q11399")
            readGenres("Reset Act", "Q11399", match = "UNCHECKED")

            sync(described, listOf("Jazz Act" to ArtistRole.HEADLINER))
            sync(supported, bill)

            tagsOf(described) shouldBe mapOf("Jazz" to "DESCRIPTION")
            tagsOf(supported) shouldBe emptyMap()
        }
    }

    /** Der Weiße Hase's house Techno on a goth night made its DJs techno DJs at Frannz Club (#5387). */
    @Test
    fun `a house genre lends no tag to the acts' other nights, and a genre the house venue publishes still does`() {
        runBlocking {
            val house = persistEvent("der-weisse-hase:1", eventType = "PARTY", sourceType = "DER_WEISSE_HASE", genre = "Techno")
            sync(house, listOf("Bjoern" to ArtistRole.DJ), genre = "Techno")
            val published = persistEvent("kitkatclub:1", eventType = "PARTY", sourceType = "KITKATCLUB", genre = "Post-Punk")
            sync(published, listOf("Wilco" to ArtistRole.DJ), genre = "Post-Punk")
            val frannz = persistEvent("frannz:1", eventType = "PARTY", sourceType = "FRANNZ")
            sync(frannz, listOf("Bjoern" to ArtistRole.DJ, "Wilco" to ArtistRole.DJ))

            tagsOf(house) shouldBe mapOf("Techno" to "SOURCE")
            tagsOf(frannz) shouldBe mapOf("Post-Punk" to "ARTIST")
        }
    }

    @Test
    fun `a derived tag never feeds another derivation`() {
        runBlocking {
            sync(persistEvent("lido:3"), listOf("Act A" to ArtistRole.HEADLINER), genre = "Jazz")
            val middle = persistEvent("so36:3")
            sync(middle, listOf("Act A" to ArtistRole.HEADLINER, "Act B" to ArtistRole.HEADLINER))
            val last = persistEvent("bi-nuu:3")
            sync(last, listOf("Act B" to ArtistRole.HEADLINER))

            tagsOf(middle) shouldBe mapOf("Jazz" to "ARTIST")
            tagsOf(last) shouldBe emptyMap()
        }
    }

    @Test
    fun `an event with no tagged act takes the genre words of its description, outside a support clause`() {
        runBlocking {
            val night = persistEvent("so36:4", description = "Ein Abend voller Techno. Support: eine Metal-Band.")
            sync(night, listOf("Unknown Act" to ArtistRole.HEADLINER))

            tagsOf(night) shouldBe mapOf("Techno" to GenreTagOrigin.DESCRIPTION.name)
        }
    }

    @Test
    fun `an untagged night takes the genre of a hyphenated format word in its title`() {
        runBlocking {
            val night = persistEvent("so36:title", eventType = "PARTY", title = "Jazz-Nacht im Toenz", description = "Mit DJ Unknown")
            sync(night)

            tagsOf(night) shouldBe mapOf("Jazz" to GenreTagOrigin.DESCRIPTION.name)
        }
    }

    @Test
    fun `an untagged night takes the genre words of its subtitle (#5388)`() {
        runBlocking {
            val night = persistEvent("so36:subtitle", eventType = "PARTY", title = "LA CASA DEL PERREO", subtitle = "Reggaeton * Urban Latin")
            sync(night)

            tagsOf(night) shouldBe mapOf("Latin" to GenreTagOrigin.DESCRIPTION.name)
        }
    }

    @Test
    fun `a subtitle that is a tour name or a support line gives no genre`() {
        runBlocking {
            val tour = persistEvent("so36:tour", subtitle = "NEMO TOUR 2027")
            sync(tour)
            val support = persistEvent("so36:support", subtitle = "Support: Kann Karate (Metal)")
            sync(support)

            tagsOf(tour) shouldBe emptyMap()
            tagsOf(support) shouldBe emptyMap()
        }
    }

    @Test
    fun `a subtitle's genre words do not touch an event with a source tag`() {
        runBlocking {
            val night = persistEvent("so36:subtitle-source", eventType = "PARTY", subtitle = "Reggaeton * Urban Latin")
            sync(night, genre = "Techno")

            tagsOf(night) shouldBe mapOf("Techno" to "SOURCE")
        }
    }

    @Test
    fun `the subtitle's genre words rank before the description's, at most three in all`() {
        runBlocking {
            val night = persistEvent("so36:subtitle-order", subtitle = "Jazz", description = "Punk, Techno, Metal und Soul")
            sync(night)

            tagsOf(night) shouldBe mapOf("Jazz" to "DESCRIPTION", "Punk" to "DESCRIPTION", "Techno" to "DESCRIPTION")
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

package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRequestFixtures
import de.norm.events.artist.ArtistResponse
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagOrigin
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

/** An admin create or edit derives genre tags as an import does, unless the event pins its genre tags (#5262, ADR-047). */
class EventGenreDerivationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventGenreTagRepository: EventGenreTagRepository

    @Autowired
    private lateinit var genreTagRepository: GenreTagRepository

    @Test
    fun `a created event takes the source tags its billed act carries on another event`() {
        val venue = createVenue("Derive Create Venue")
        val act = createArtist("Derive Create Act")
        createEvent(venue.id, "test:derive-create-1", act, genre = "Post-Punk")

        val created = createEvent(venue.id, "manual:derive-create-2", act)

        created.genreTags shouldBe listOf("Post-Punk")
        tagsOf(created.id) shouldBe mapOf("Post-Punk" to GenreTagOrigin.ARTIST.name)
    }

    @Test
    fun `a created event with a genre of its own keeps only that genre`() {
        val venue = createVenue("Derive Own Venue")
        val act = createArtist("Derive Own Act")
        createEvent(venue.id, "test:derive-own-1", act, genre = "Jazz")

        val created = createEvent(venue.id, "manual:derive-own-2", act, genre = "Techno")

        created.genreTags shouldBe listOf("Techno")
        tagsOf(created.id) shouldBe mapOf("Techno" to GenreTagOrigin.SOURCE.name)
    }

    @Test
    fun `re-saving an unchanged event derives the tags its act gained since, and pins nothing`() {
        val venue = createVenue("Derive Resave Venue")
        val act = createArtist("Derive Resave Act")
        val untagged = createEvent(venue.id, "manual:derive-resave-1", act)
        untagged.genreTags shouldBe emptyList()
        createEvent(venue.id, "test:derive-resave-2", act, genre = "Noise")

        val resaved = update(untagged.id, request(venue.id, "manual:derive-resave-1", act))

        resaved.genreTags shouldBe listOf("Noise")
        resaved.pinnedFields shouldBe emptyList()
        tagsOf(untagged.id) shouldBe mapOf("Noise" to GenreTagOrigin.ARTIST.name)
    }

    @Test
    fun `an update of an event with pinned genre tags derives nothing`() {
        val venue = createVenue("Derive Pinned Venue")
        val act = createArtist("Derive Pinned Act")
        createEvent(venue.id, "test:derive-pinned-1", act, genre = "Metal")
        val event = createEvent(venue.id, "manual:derive-pinned-2", artist = null, genre = "Folk")

        // Clearing the genre pins the genre tags, and the act this edit adds lends none.
        val cleared = update(event.id, request(venue.id, "manual:derive-pinned-2", act))
        cleared.pinnedFields shouldContain PinnedField.GENRES.key
        cleared.genreTags shouldBe emptyList()

        // A later edit of a pinned event derives nothing either.
        val resaved = update(event.id, request(venue.id, "manual:derive-pinned-2", act, title = "Renamed night"))
        resaved.genreTags shouldBe emptyList()
        tagsOf(event.id) shouldBe emptyMap()
    }

    @Test
    fun `an update that adds an act derives its tags when the genre tags are not pinned`() {
        val venue = createVenue("Derive Lineup Venue")
        val act = createArtist("Derive Lineup Act")
        createEvent(venue.id, "test:derive-lineup-1", act, genre = "Ambient")
        val event = createEvent(venue.id, "manual:derive-lineup-2", artist = null)

        val updated = update(event.id, request(venue.id, "manual:derive-lineup-2", act))

        updated.pinnedFields shouldContain PinnedField.LINEUP.key
        updated.pinnedFields shouldNotContain PinnedField.GENRES.key
        updated.genreTags shouldBe listOf("Ambient")
    }

    private fun request(
        venueId: Long,
        sourceId: String,
        artist: ArtistResponse?,
        genre: String? = null,
        title: String = "Night $sourceId"
    ): EventRequest =
        EventRequestFixtures.create(
            venueId = venueId,
            title = title,
            sourceId = sourceId,
            eventDate = LocalDate.of(2026, 12, 5),
            genre = genre,
            artists = listOfNotNull(artist?.let { EventArtistRequest(artistId = it.id) })
        )

    private fun createEvent(
        venueId: Long,
        sourceId: String,
        artist: ArtistResponse?,
        genre: String? = null
    ): EventResponse =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request(venueId, sourceId, artist, genre))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private fun update(
        id: Long,
        request: EventRequest
    ): EventResponse =
        webTestClient
            .put()
            .uri("/api/admin/events/$id")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private fun createVenue(name: String): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.create(name = name))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun createArtist(name: String): ArtistResponse =
        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = name))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    /** The stored tags of the event as name to origin. */
    private fun tagsOf(eventId: Long): Map<String, String> =
        runBlocking {
            val links = eventGenreTagRepository.findByEventIdIn(listOf(eventId)).toList()
            val names = genreTagRepository.findAllById(links.map { it.genreTagId }).toList().associate { it.id to it.name }
            links.associate { requireNotNull(names[it.genreTagId]) to it.origin }
        }
}

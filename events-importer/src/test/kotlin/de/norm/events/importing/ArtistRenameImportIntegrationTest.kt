package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.ArtistRequestFixtures
import de.norm.events.artist.ArtistResponse
import de.norm.events.artist.ArtistSlugAliasStore
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.PinnedField
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

/**
 * A rename through the admin API survives the next import of an event that bills the old name (#3289).
 * The rename changes the slug, so only the former slug kept as an alias leads the import back to the row.
 */
class ArtistRenameImportIntegrationTest : BaseControllerTest() {
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

    @Autowired
    private lateinit var aliasStore: ArtistSlugAliasStore

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `an import after a rename bills the renamed artist and creates no row for the old name`() {
        runBlocking {
            val event = persistEvent("cassiopeia:2026-12-10-a-night-of-beatbox-with-marvelin")
            val oldName = "A Night of Beatbox with Marvelin"
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, oldName, "Kid Marvel")))
            val artist = requireNotNull(artistRepository.findBySlug("a-night-of-beatbox-with-marvelin"))
            val rowsBefore = artistRepository.count()

            rename(requireNotNull(artist.id), "Marvelin").slug shouldBe "marvelin"
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, oldName, "Kid Marvel")))

            billed(event) shouldContainExactly listOf("Marvelin", "Kid Marvel")
            artistRepository.count() shouldBe rowsBefore
            artistRepository.findBySlug("a-night-of-beatbox-with-marvelin") shouldBe null
        }
    }

    @Test
    fun `a second rename keeps both former slugs, and a rename back takes its slug back`() {
        runBlocking {
            val event = persistEvent("venue:chain")
            val id = requireNotNull(createArtist("DJ Foo (live)").id)

            rename(id, "DJ Foo")
            rename(id, "Foo")
            aliasStore.artistIdsBySlug(listOf("dj-foo-live", "dj-foo")) shouldBe mapOf("dj-foo-live" to id, "dj-foo" to id)

            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, "DJ Foo (live)")))
            billed(event) shouldContainExactly listOf("Foo")

            rename(id, "DJ Foo").slug shouldBe "dj-foo"
            aliasStore.artistIdsBySlug(listOf("dj-foo", "foo")) shouldBe mapOf("foo" to id)
        }
    }

    @Test
    fun `a renamed artist's lineup pin does not count as changed by the old name`() {
        runBlocking {
            val event = persistEvent("venue:pinned")
            associationSyncService.resolveAndSyncAssociations(listOf(event), listOf(scraped(event, "Old Name")))
            val id = requireNotNull(artistRepository.findBySlug("old-name")?.id)
            rename(id, "New Name")
            val pinned = eventRepository.save(event.copy(pinnedFields = listOf(PinnedField.LINEUP.key)))

            associationSyncService.resolveAndSyncAssociations(listOf(pinned), listOf(scraped(pinned, "Old Name"))).pinsKept shouldBe 0
            billed(pinned) shouldContainExactly listOf("New Name")
        }
    }

    @Test
    fun `creating an artist whose slug is another artist's former slug answers 409`() {
        val renamed = createArtist("Marvelin Live")
        rename(renamed.id, "Marvelin")

        webTestClient
            .post()
            .uri("/api/admin/artists")
            .bodyValue(ArtistRequestFixtures.create(name = "Marvelin LIVE"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo(
                "Slug 'marvelin-live' (generated from name 'Marvelin LIVE') is the former slug of artist ${renamed.id}, which the import still bills under it"
            )
    }

    @Test
    fun `renaming an artist to another artist's former slug answers 409`() {
        val renamed = createArtist("Old Act")
        rename(renamed.id, "New Act")
        val other = createArtist("Other Act")

        webTestClient
            .put()
            .uri("/api/admin/artists/${other.id}")
            .bodyValue(ArtistRequestFixtures.create(name = "Old Act"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `deleting an artist deletes its former slugs, and the old name is free again`() {
        runBlocking {
            val renamed = createArtist("Gone Act")
            rename(renamed.id, "Gone")
            webTestClient
                .delete()
                .uri("/api/admin/artists/${renamed.id}")
                .exchange()
                .expectStatus()
                .isNoContent

            aliasStore.artistIdsBySlug(listOf("gone-act")).shouldBeEmpty()
            createArtist("Gone Act").slug shouldBe "gone-act"
        }
    }

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

    private fun rename(
        id: Long,
        name: String
    ): ArtistResponse =
        webTestClient
            .put()
            .uri("/api/admin/artists/$id")
            .bodyValue(ArtistRequestFixtures.create(name = name))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<ArtistResponse>()
            .returnResult()
            .responseBody!!

    private suspend fun persistEvent(sourceId: String): EventEntity {
        val venue = venueRepository.save(VenueEntity(name = "Venue $sourceId", slug = "venue-${sourceId.replace(':', '-')}"))
        return eventRepository.save(
            EventEntity(
                venueId = requireNotNull(venue.id),
                title = sourceId,
                slug = sourceId.replace(':', '-'),
                eventDate = LocalDate.of(2026, 12, 10),
                sourceId = sourceId
            )
        )
    }

    private fun scraped(
        event: EventEntity,
        vararg artists: String
    ) = ScrapedEvent(
        title = event.title,
        eventDate = event.eventDate,
        sourceId = event.sourceId,
        sourceUrl = "https://example.com/event",
        eventType = "CONCERT",
        status = "SCHEDULED",
        artists = artists.map { ScrapedArtist(name = it) }
    )

    /** The billed names of [event], in billing order. */
    private suspend fun billed(event: EventEntity): List<String> =
        eventArtistRepository
            .findByEventIdIn(listOf(requireNotNull(event.id)))
            .toList()
            .sortedBy { it.billingOrder }
            .map { requireNotNull(artistRepository.findById(it.artistId)).name }
}

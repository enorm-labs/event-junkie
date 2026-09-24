package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.promoter.PromoterRepository
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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

    // #301: the format suffix comes off every line-up entry here, so an act billed `(live)` by one
    // venue and bare by another is one row, and a line-up that bills both spellings is one link.
    @Test
    fun `a performance-format suffix is stripped at sync, for every source`() {
        runBlocking {
            val sourceId = "format-suffix:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(
                        sourceId,
                        artists =
                            listOf(
                                ScrapedArtist(name = "C3D-E (live)", role = "DJ"),
                                ScrapedArtist(name = "Avangelic (DJ-Set)", role = "DJ"),
                                ScrapedArtist(name = "Avangelic", role = "DJ"),
                                ScrapedArtist(name = "Regis Live & DJ set", role = "DJ")
                            )
                    )
                )
            )

            artistRepository.findBySlug("c3d-e")?.name shouldBe "C3D-E"
            artistRepository.findBySlug("avangelic")?.name shouldBe "Avangelic"
            artistRepository.findBySlug("regis")?.name shouldBe "Regis"
            artistRepository.findBySlug("c3d-e-live") shouldBe null
            eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().size shouldBe 3
        }
    }

    // #1761: a guest in a bracket is a second act, split at sync for every source.
    @Test
    fun `a bracketed guest becomes its own act, for every source`() {
        runBlocking {
            val sourceId = "bracketed-guest:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(scraped(sourceId, artists = listOf(ScrapedArtist(name = "Dosenstolz (feat. Tancred)", role = "HEADLINER"))))
            )

            artistRepository.findBySlug("dosenstolz")?.name shouldBe "Dosenstolz"
            artistRepository.findBySlug("tancred")?.name shouldBe "Tancred"
            eventArtistRepository
                .findByEventIdIn(listOf(requireNotNull(event.id)))
                .toList()
                .map { it.role }
                .sorted() shouldBe
                listOf("HEADLINER", "SUPPORT")
        }
    }

    // #302: a series or album glued to an act with a dash comes off when the catalogue already holds
    // the bare act as a MusicBrainz EXACT row. MusicBrainz is the vocabulary; nothing else is listed.
    @Test
    fun `a dashed name links to its head when the head is a verified row, and stays glued otherwise`() {
        runBlocking {
            val sourceId = "series-tail:1"
            val event = persistEvent(sourceId)
            artistRepository.insertIfAbsent("Xmal Deutschland", "xmal-deutschland")
            val xmal = requireNotNull(artistRepository.findBySlug("xmal-deutschland"))
            artistRepository.storeMusicBrainzVerdict(requireNotNull(xmal.id), MusicBrainzMatch.EXACT.name, "8f9a5b9c-0000-0000-0000-000000000000")
            // Known, but never verified: the head decides nothing.
            artistRepository.insertIfAbsent("Alister Spence", "alister-spence")

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(
                        sourceId,
                        artists =
                            listOf(
                                ScrapedArtist(name = "Xmal Deutschland – Sonic Morgue"),
                                ScrapedArtist(name = "Alister Spence – Within Without"),
                                ScrapedArtist(name = "Current 93 – Sonic Morgue")
                            )
                    )
                )
            )

            val linked = eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().map { it.artistId }
            linked shouldContain requireNotNull(xmal.id)
            artistRepository.findBySlug("xmal-deutschland-sonic-morgue") shouldBe null
            artistRepository.findBySlug("alister-spence-within-without")?.name shouldBe "Alister Spence – Within Without"
            artistRepository.findBySlug("current-93-sonic-morgue")?.name shouldBe "Current 93 – Sonic Morgue"
            linked.size shouldBe 3
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

    // A title that resolves to a festival at the boundary keeps its line-up and loses the headliner
    // the scraper read off the title before the type was final (#300).
    @Test
    fun `a festival title drops its title-derived headliner and keeps the published line-up`() {
        runBlocking {
            val sourceId = "festival-title:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(sourceId, artists = listOf(ScrapedArtist(name = "Elle", titleDerived = true), ScrapedArtist(name = "Real Band")))
                        .copy(title = "ELLE & L's Festival")
                )
            )

            val stored = artistRepository.findAll().toList().map { it.name }
            stored shouldBe listOf("Real Band")
        }
    }

    // A secret-lineup night is titled after the series that promotes it, and the title-as-headliner
    // default then mints the promoter as the act (#1772).
    @Test
    fun `a title-derived name the event credits as its promoter is not stored as an artist`() {
        runBlocking {
            val sourceId = "promoter-title:1"
            val event = persistEvent(sourceId)

            val touched =
                associationSyncService.resolveAndSyncAssociations(
                    listOf(event),
                    listOf(
                        scraped(sourceId, artists = listOf(ScrapedArtist(name = "Unreleased Berlin", role = "HEADLINER", titleDerived = true)))
                            .copy(title = "UNRELEASED BERLIN", promoters = listOf("Unreleased Berlin"))
                    )
                )

            // Held back, not billed: the row exists only for the MusicBrainz sweep to look at (#1841).
            eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().shouldBeEmpty()
            val held = artistRepository.findAll().toList()
            held.map { it.name } shouldBe listOf("Unreleased Berlin")
            touched shouldBe setOf(requireNotNull(held.single().id))
        }
    }

    // A band can promote its own show: Urban Spree credits `WISBORG` for `WISBORG Phantomschmerz Tour` (#1841).
    @Test
    fun `a title-derived promoter name that MusicBrainz knows as an act is billed`() {
        runBlocking {
            artistRepository.save(ArtistEntity(name = "Wisborg", slug = "wisborg", musicbrainzMatch = MusicBrainzMatch.EXACT.name))
            val sourceId = "promoter-exact:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(sourceId, artists = listOf(ScrapedArtist(name = "WISBORG", role = "HEADLINER", titleDerived = true)))
                        .copy(title = "WISBORG Phantomschmerz Tour", promoters = listOf("WISBORG"))
                )
            )

            val linked = eventArtistRepository.findByEventIdIn(listOf(requireNotNull(event.id))).toList().map { it.artistId }
            artistRepository
                .findAll()
                .toList()
                .filter { it.id in linked }
                .map { it.name } shouldBe listOf("Wisborg")
        }
    }

    // Only a name no line-up stated is dropped: a venue that promotes its own night still plays it.
    @Test
    fun `an act the venue billed is kept even when it promotes the same night`() {
        runBlocking {
            val sourceId = "promoter-billed:1"
            val event = persistEvent(sourceId)

            associationSyncService.resolveAndSyncAssociations(
                listOf(event),
                listOf(
                    scraped(sourceId, artists = listOf(ScrapedArtist(name = "Unreleased Berlin", role = "HEADLINER")))
                        .copy(title = "UNRELEASED BERLIN", promoters = listOf("Unreleased Berlin"))
                )
            )

            artistRepository.findAll().toList().map { it.name } shouldBe listOf("Unreleased Berlin")
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

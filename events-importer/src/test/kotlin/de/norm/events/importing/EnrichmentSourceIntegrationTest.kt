package de.norm.events.importing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventRepository
import de.norm.events.event.EventStatus
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * ADR-043 through [EventUpsertService], [EventEnrichmentService] and the association sync against
 * a real PostgreSQL: an enrichment source fills only empty, unpinned fields of the events the main
 * source lists, never inserts or deletes, drops and counts what matches nothing, and the main
 * source's own value wins at its next import.
 */
class EnrichmentSourceIntegrationTest : BaseControllerTest() {
    @Autowired private lateinit var eventUpsertService: EventUpsertService

    @Autowired private lateinit var eventEnrichmentService: EventEnrichmentService

    @Autowired private lateinit var enrichmentRepository: EventEnrichmentRepository

    @Autowired private lateinit var eventRepository: EventRepository

    @Autowired private lateinit var eventArtistRepository: EventArtistRepository

    @Autowired private lateinit var eventPromoterRepository: EventPromoterRepository

    @Autowired private lateinit var eventGenreTagRepository: EventGenreTagRepository

    @Autowired private lateinit var genreTagRepository: GenreTagRepository

    @Autowired private lateinit var artistRepository: ArtistRepository

    @Autowired private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired private lateinit var venueRepository: VenueRepository

    private var venueId: Long = 0
    private var mainSourceId: Long = 0
    private var enrichmentSourceId: Long = 0
    private val venueSlug = "lido"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Lido", slug = venueSlug)).id)
            mainSourceId = source("lido", SourceRole.MAIN)
            enrichmentSourceId = source("puschen", SourceRole.ENRICHMENT)
        }
    }

    private suspend fun source(
        slug: String,
        role: SourceRole
    ): Long =
        requireNotNull(
            eventSourceRepository
                .save(
                    EventSourceEntity(
                        venueId = venueId,
                        name = slug,
                        slug = slug,
                        url = "https://$slug.example/",
                        sourceType = slug.uppercase(),
                        role = role.name
                    )
                ).id
        )

    /** The show as the venue lists it: a title, a date, a start, a ticket link, nothing else. */
    private fun venueListing(
        title: String = "Alpha Band",
        start: LocalTime? = LocalTime.of(20, 0),
        genre: String? = null,
        artists: List<ScrapedArtist> = emptyList(),
        status: String = EventStatus.SCHEDULED.name
    ) = ScrapedEvent(
        title = title,
        eventType = "CONCERT",
        eventDate = date,
        startTime = start,
        sourceId = "lido:${title.lowercase().replace(' ', '-')}",
        sourceUrl = "https://lido.example/${title.lowercase().replace(' ', '-')}",
        ticketUrl = "https://lido.example/tickets",
        genre = genre,
        artists = artists,
        status = status
    )

    /** The same show as the promoter lists it, with the support act, the genre and a price. */
    private fun promoterListing(
        title: String = "Puschen presents: Alpha Band",
        on: LocalDate = date,
        start: LocalTime? = LocalTime.of(20, 30)
    ) = ScrapedEvent(
        title = title,
        eventType = "CONCERT",
        eventDate = on,
        startTime = start,
        doorsTime = LocalTime.of(19, 0),
        sourceId = "puschen:${title.lowercase().replace(' ', '-')}",
        sourceUrl = "https://puschen.example/${title.lowercase().replace(' ', '-').replace(":", "")}",
        ticketUrl = "https://puschen.example/tickets",
        genre = "Techno",
        pricePresale = BigDecimal("22.00"),
        artists = listOf(ScrapedArtist("Alpha Band", "HEADLINER"), ScrapedArtist("Beta Support", "SUPPORT")),
        promoters = listOf("Puschen")
    )

    private suspend fun importMain(vararg events: ScrapedEvent) = eventUpsertService.upsertAndCleanup(events.toList(), venueId, venueSlug, mainSourceId)

    private suspend fun enrich(vararg events: ScrapedEvent) = eventEnrichmentService.enrich(events.toList(), venueId, venueSlug, enrichmentSourceId)

    private suspend fun stored(title: String = "Alpha Band"): EventEntity =
        eventRepository
            .findAll()
            .toList()
            .single { it.title == title }

    private suspend fun lineupOf(event: EventEntity): List<String> {
        val links = eventArtistRepository.findByEventId(requireNotNull(event.id)).toList().sortedBy { it.billingOrder }
        val names = artistRepository.findAllById(links.map { it.artistId }).toList().associate { it.id to it.name }
        return links.map { names.getValue(it.artistId) }
    }

    private suspend fun genreTagsOf(event: EventEntity): List<String> {
        val ids = eventGenreTagRepository.findByEventId(requireNotNull(event.id)).toList().map { it.genreTagId }
        return genreTagRepository.findAllById(ids).toList().map { it.name }
    }

    private suspend fun recordOf(event: EventEntity): EventEnrichment? = enrichmentRepository.findByEventIds(listOf(requireNotNull(event.id))).singleOrNull()

    @Test
    fun `an enrichment source fills only the empty fields of the main event, inserts nothing and records what it filled`(): Unit =
        runBlocking {
            importMain(venueListing())

            val outcome = enrich(promoterListing())

            outcome.matched shouldBe 1
            outcome.unmatched shouldBe 0
            eventRepository.count() shouldBe 1
            val event = stored()
            event.eventSourceId shouldBe mainSourceId
            event.title shouldBe "Alpha Band"
            event.startTime shouldBe LocalTime.of(20, 0)
            event.ticketUrl shouldBe "https://lido.example/tickets"
            event.doorsTime shouldBe LocalTime.of(19, 0)
            event.genre shouldBe "Techno"
            event.pricePresale shouldBe BigDecimal("22.00")
            lineupOf(event) shouldContainExactly listOf("Alpha Band", "Beta Support")
            genreTagsOf(event) shouldContainExactly listOf("Techno")
            eventPromoterRepository.findByEventId(requireNotNull(event.id)).toList().size shouldBe 1
            val record = requireNotNull(recordOf(event))
            record.eventSourceId shouldBe enrichmentSourceId
            record.sourceUrl shouldBe "https://puschen.example/puschen-presents-alpha-band"
            record.fields shouldContainExactlyInAnyOrder setOf("doorsTime", "genre", "pricePresale", "lineup", "promoters", "genres")
            outcome.fieldsFilled shouldBe 6
        }

    @Test
    fun `an enrichment source leaves a pinned field and a field the venue set as they are`(): Unit =
        runBlocking {
            importMain(venueListing(artists = listOf(ScrapedArtist("Alpha Band"))))
            eventRepository.save(stored().copy(pinnedFields = listOf("doorsTime")))

            enrich(promoterListing())

            val event = stored()
            event.doorsTime.shouldBeNull()
            lineupOf(event) shouldContainExactly listOf("Alpha Band")
            requireNotNull(recordOf(event)).fields shouldContainExactlyInAnyOrder setOf("genre", "pricePresale", "promoters", "genres")
        }

    @Test
    fun `an enrichment event that matches no main event is dropped, counted and logged, and nothing is created`(): Unit =
        runBlocking {
            importMain(venueListing())

            val (outcome, lines) =
                capturingLogs {
                    enrich(
                        promoterListing(title = "Other Day", on = date.plusDays(1)),
                        promoterListing(title = "Late Show", start = LocalTime.of(23, 0))
                    )
                }

            outcome.matched shouldBe 0
            outcome.unmatched shouldBe 2
            eventRepository.count() shouldBe 1
            stored().genre.shouldBeNull()
            lines.count { it.level == Level.INFO && it.formattedMessage.startsWith("Dropped enrichment event") } shouldBe 2
            lines.single { it.level == Level.INFO && it.formattedMessage.contains("0 matched, 2 unmatched") }
        }

    @Test
    fun `when two main events fit, the title decides, and a tie fills neither`(): Unit =
        runBlocking {
            importMain(venueListing(title = "Alpha Band", start = LocalTime.of(20, 0)), venueListing(title = "Gamma Quartet", start = LocalTime.of(20, 45)))

            val decided = enrich(promoterListing(title = "Puschen presents: Gamma Quartet", start = LocalTime.of(20, 30)))
            val tied = enrich(promoterListing(title = "Something Else", start = LocalTime.of(20, 30)).copy(genre = "Jazz"))

            decided.matched shouldBe 1
            stored("Gamma Quartet").genre shouldBe "Techno"
            stored("Alpha Band").genre.shouldBeNull()
            tied.ambiguous shouldBe 1
            tied.matched shouldBe 0
        }

    @Test
    fun `a main event without a start time matches on the date alone`(): Unit =
        runBlocking {
            importMain(venueListing(start = null))

            enrich(promoterListing())

            stored().startTime shouldBe LocalTime.of(20, 30)
        }

    @Test
    fun `the main source keeps an enriched value it lacks, and its own value wins and takes the credit off`(): Unit =
        runBlocking {
            importMain(venueListing())
            enrich(promoterListing())

            importMain(venueListing())
            val kept = stored()
            kept.genre shouldBe "Techno"
            kept.doorsTime shouldBe LocalTime.of(19, 0)
            lineupOf(kept) shouldContainExactly listOf("Alpha Band", "Beta Support")
            genreTagsOf(kept) shouldContainExactly listOf("Techno")

            importMain(venueListing(genre = "Punk", artists = listOf(ScrapedArtist("Alpha Band"))))
            val won = stored()
            won.genre shouldBe "Punk"
            genreTagsOf(won) shouldContainExactly listOf("Punk")
            lineupOf(won) shouldContainExactly listOf("Alpha Band")
            won.doorsTime shouldBe LocalTime.of(19, 0)
            requireNotNull(recordOf(won)).fields shouldContainExactlyInAnyOrder setOf("doorsTime", "pricePresale", "promoters")
        }

    @Test
    fun `a record whose every field the main source sets is deleted`(): Unit =
        runBlocking {
            importMain(venueListing())
            enrich(promoterListing().copy(doorsTime = null, pricePresale = null, artists = emptyList(), promoters = emptyList()))
            requireNotNull(recordOf(stored())).fields shouldContainExactlyInAnyOrder setOf("genre", "genres")

            importMain(venueListing(genre = "Punk"))

            recordOf(stored()).shouldBeNull()
        }

    @Test
    fun `a cancelled main event is filled like any other, and an enrichment run deletes nothing`(): Unit =
        runBlocking {
            importMain(venueListing(status = EventStatus.CANCELLED.name), venueListing(title = "Untouched", start = LocalTime.of(14, 0)))

            enrich(promoterListing())

            stored().status shouldBe EventStatus.CANCELLED.name
            stored().genre shouldBe "Techno"
            stored("Untouched").genre.shouldBeNull()
            eventRepository.count() shouldBe 2
        }

    @Test
    fun `a past enrichment event is dropped before matching`(): Unit =
        runBlocking {
            val outcome = enrich(promoterListing(on = LocalDate.now(BERLIN).minusDays(3)))

            outcome.matched shouldBe 0
            outcome.unmatched shouldBe 0
            eventRepository.findAll().toList().shouldBeEmpty()
        }

    private suspend fun <T> capturingLogs(block: suspend () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(EventEnrichmentService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block() to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
        }
    }
}

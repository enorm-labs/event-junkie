package de.norm.events.importing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.event.EventArtistRequest
import de.norm.events.event.EventRepository
import de.norm.events.event.EventRequest
import de.norm.events.event.EventResponse
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate
import java.time.LocalTime

/**
 * ADR-042 through the admin API and the importer merge against a real PostgreSQL: a hand edit pins
 * what it changes, the import keeps a pinned column and join table and updates the rest, and a
 * removed pin takes the source's value at the next import.
 */
class PinnedFieldImportIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventUpsertService: EventUpsertService

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    @Autowired
    private lateinit var venueRepository: VenueRepository

    private var venueId: Long = 0
    private var eventSourceId: Long = 0
    private val venueSlug = "astra-kulturhaus"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Astra Kulturhaus", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Astra",
                                slug = venueSlug,
                                url = "https://www.astra-berlin.de/",
                                sourceType = "ASTRA",
                                enabled = true
                            )
                        ).id
                )
        }
    }

    /** The show as the venue publishes it. */
    private fun published(priceNote: String = "Presale only") =
        ScrapedEvent(
            title = "The Adicts",
            eventType = "CONCERT",
            eventDate = date,
            startTime = LocalTime.of(20, 0),
            sourceId = "astra:the-adicts",
            sourceUrl = "https://www.astra-berlin.de/events/the-adicts",
            genre = "Punk",
            priceNote = priceNote,
            artists = listOf(ScrapedArtist("The Adicts", "HEADLINER"), ScrapedArtist("Maid of Ace", "SUPPORT")),
            promoters = listOf("Loft Concerts")
        )

    private suspend fun import(event: ScrapedEvent) = eventUpsertService.upsertAndCleanup(listOf(event), venueId, venueSlug, eventSourceId)

    private suspend fun storedId(): Long =
        requireNotNull(
            eventRepository
                .findBySourceIdIn(listOf("astra:the-adicts"))
                .toList()
                .single()
                .id
        )

    @Test
    fun `an edit pins what it changes, the import keeps the pins and updates the rest, and an unpinned field follows the source again`(): Unit =
        runBlocking {
            import(published())
            val id = storedId()
            val before = get(id)
            val headliner = before.artists.single { it.billingOrder == 0 }

            val edited =
                put(
                    id,
                    before.toRequest().copy(
                        title = "The Adicts (live)",
                        genre = "Ska",
                        artists = listOf(EventArtistRequest(headliner.artistId, headliner.role, 0, headliner.stage))
                    )
                )
            edited.pinnedFields shouldContainExactlyInAnyOrder listOf("title", "genre", "lineup", "genres")

            val (outcome, lines) = capturingLogs { import(published(priceNote = "Box office only")) }

            outcome.pinsKept shouldBe 4
            val kept = lines.filter { it.level == Level.DEBUG && it.formattedMessage.startsWith("Kept the pinned") }
            kept.map { it.formattedMessage.substringAfter("pinned ").substringBefore(" of") } shouldContainExactlyInAnyOrder
                listOf("title", "genre", "lineup", "genres")
            kept.forEach { line -> line.keyValuePairs.single { it.key == LogFields.EVENT_ID }.value shouldBe id }
            lines.single { it.level == Level.INFO && it.formattedMessage.startsWith("Kept 4 pinned field(s)") }
            val stored = get(id)
            stored.title shouldBe "The Adicts (live)"
            stored.slug shouldBe edited.slug
            stored.genre shouldBe "Ska"
            stored.genreTags shouldBe listOf("Ska")
            stored.artists.map { it.artistId } shouldBe listOf(headliner.artistId)
            stored.priceNote shouldBe "Box office only"
            stored.promoterIds shouldBe before.promoterIds
            stored.pinnedFields shouldContainExactlyInAnyOrder listOf("title", "genre", "lineup", "genres")

            unpin(id, "title")
            unpin(id, "lineup")
            get(id).pinnedFields shouldContainExactlyInAnyOrder listOf("genre", "genres")

            import(published(priceNote = "Box office only")).pinsKept shouldBe 2
            val released = get(id)
            released.title shouldBe "The Adicts"
            released.artists.size shouldBe 2
            released.genre shouldBe "Ska"
        }

    @Test
    fun `an edit that changes nothing pins nothing, an unknown pin is refused, and a missing pin is no error`(): Unit =
        runBlocking {
            import(published())
            val id = storedId()

            put(id, get(id).toRequest()).pinnedFields shouldBe emptyList()

            webTestClient
                .delete()
                .uri("/api/admin/events/$id/pins/sourceId")
                .exchange()
                .expectStatus()
                .isBadRequest
            unpin(id, "title")
        }

    /** Runs [block] with both import loggers captured at DEBUG. */
    private suspend fun <T> capturingLogs(block: suspend () -> T): Pair<T, List<ILoggingEvent>> {
        val loggers = listOf(EventUpsertService::class, AssociationSyncService::class).map { LoggerFactory.getLogger(it.java) as Logger }
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val levels = loggers.map { it.level }
        loggers.forEach {
            it.level = Level.DEBUG
            it.addAppender(appender)
        }
        try {
            return block() to appender.list.toList()
        } finally {
            loggers.zip(levels).forEach { (logger, level) ->
                logger.detachAppender(appender)
                logger.level = level
            }
        }
    }

    private fun get(id: Long): EventResponse =
        webTestClient
            .get()
            .uri("/api/admin/events/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    private fun put(
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

    private fun unpin(
        id: Long,
        field: String
    ) {
        webTestClient
            .delete()
            .uri("/api/admin/events/$id/pins/$field")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    /** The request that sends every editable value back unchanged. */
    private fun EventResponse.toRequest() =
        EventRequest(
            venueId = venueId,
            title = title,
            subtitle = subtitle,
            description = description,
            eventType = eventType,
            status = status,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = imageUrl,
            sourceUrl = sourceUrl,
            sourceId = sourceId,
            ticketUrl = ticketUrl,
            facebookEventUrl = facebookEventUrl,
            genre = genre,
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            priceCurrency = priceCurrency,
            priceNote = priceNote,
            soldOut = soldOut,
            free = free,
            artists = artists.map { EventArtistRequest(it.artistId, it.role, it.billingOrder, it.stage) },
            promoterIds = promoterIds
        )
}

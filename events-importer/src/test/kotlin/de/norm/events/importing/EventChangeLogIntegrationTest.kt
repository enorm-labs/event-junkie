package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventArtistRequest
import de.norm.events.event.EventRepository
import de.norm.events.event.EventRequest
import de.norm.events.event.EventResponse
import de.norm.events.event.EventStatus
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate
import java.time.LocalTime

/**
 * `event_change` against a real PostgreSQL (#2725): an insert logs nothing, an update logs each
 * tracked field it moves, a pinned field the source would move logs nothing, an admin edit logs what
 * it pins, and a deleted event takes its changes with it.
 */
class EventChangeLogIntegrationTest : BaseControllerTest() {
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
    private val venueSlug = "festsaal-kreuzberg"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Festsaal Kreuzberg", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Festsaal Kreuzberg",
                                slug = venueSlug,
                                url = "https://festsaal-kreuzberg.de/",
                                sourceType = "FESTSAAL",
                                enabled = true
                            )
                        ).id
                )
        }
    }

    /** A night over midnight, as the venue publishes it. */
    private fun published(
        startTime: LocalTime? = LocalTime.of(22, 0),
        eventDate: LocalDate = date,
        endTime: LocalTime? = LocalTime.of(5, 0),
        status: String = "SCHEDULED",
        priceNote: String = "Presale only"
    ) = ScrapedEvent(
        title = "A very long night of Berlin techno with far too many names on the poster to fit on one line",
        eventType = "PARTY",
        eventDate = eventDate,
        startTime = startTime,
        endDate = eventDate.plusDays(1),
        endTime = endTime,
        status = status,
        sourceId = "festsaal:long-night",
        sourceUrl = "https://festsaal-kreuzberg.de/events/long-night",
        priceNote = priceNote
    )

    private suspend fun import(event: ScrapedEvent) = eventUpsertService.upsertAndCleanup(listOf(event), venueId, venueSlug, eventSourceId)

    private suspend fun storedId(): Long =
        requireNotNull(
            eventRepository
                .findBySourceIdIn(listOf("festsaal:long-night"))
                .toList()
                .single()
                .id
        )

    private suspend fun changes(): List<Triple<String, String, String>> =
        databaseClient
            .sql("SELECT field, old_value, new_value FROM events.event_change ORDER BY id")
            .map { row ->
                Triple(
                    requireNotNull(row.get("field", String::class.java)),
                    requireNotNull(row.get("old_value", String::class.java)),
                    requireNotNull(row.get("new_value", String::class.java))
                )
            }.all()
            .collectList()
            .awaitFirstOrNull()
            .orEmpty()

    @Test
    fun `an insert logs nothing, and an update logs each moved field but not a price`(): Unit =
        runBlocking {
            import(published())
            changes().shouldBeEmpty()

            import(published(priceNote = "Box office only"))
            changes().shouldBeEmpty()

            import(published(startTime = LocalTime.of(23, 0), endTime = LocalTime.of(6, 0)))
            changes() shouldContainExactlyInAnyOrder
                listOf(Triple("START_TIME", "22:00", "23:00"), Triple("END_TIME", "05:00", "06:00"))
        }

    @Test
    fun `a new date logs the date and the end that moves with it`(): Unit =
        runBlocking {
            import(published())
            import(published(eventDate = date.plusDays(2)))

            changes() shouldContainExactlyInAnyOrder
                listOf(
                    Triple("EVENT_DATE", "$date", "${date.plusDays(2)}"),
                    Triple("END_DATE", "${date.plusDays(1)}", "${date.plusDays(3)}")
                )
        }

    @Test
    fun `every status move is logged, and a start the scrape lost is not`(): Unit =
        runBlocking {
            import(published())
            listOf("CANCELLED", "SCHEDULED", "POSTPONED", "RELOCATED").forEach { import(published(status = it)) }
            import(published(status = "RELOCATED", startTime = null))

            changes() shouldBe
                listOf(
                    Triple("STATUS", "SCHEDULED", "CANCELLED"),
                    Triple("STATUS", "CANCELLED", "SCHEDULED"),
                    Triple("STATUS", "SCHEDULED", "POSTPONED"),
                    Triple("STATUS", "POSTPONED", "RELOCATED")
                )
        }

    @Test
    fun `an admin edit logs what it pins, and the import does not log the pinned field it keeps`(): Unit =
        runBlocking {
            import(published())
            val id = storedId()

            val edited = put(id, get(id).toRequest().copy(startTime = LocalTime.of(21, 30), status = EventStatus.CANCELLED))
            edited.pinnedFields shouldContainExactlyInAnyOrder listOf("startTime", "status")
            changes() shouldContainExactlyInAnyOrder
                listOf(Triple("START_TIME", "22:00", "21:30"), Triple("STATUS", "SCHEDULED", "CANCELLED"))

            import(published(startTime = LocalTime.of(23, 0)))
            changes().size shouldBe 2
        }

    @Test
    fun `a deleted event takes its changes with it`(): Unit =
        runBlocking {
            import(published())
            import(published(status = "CANCELLED"))
            changes().size shouldBe 1

            eventRepository.deleteById(storedId())

            changes().shouldBeEmpty()
        }

    @Test
    fun `an import deletes the changes of an event that has ended`(): Unit =
        runBlocking {
            import(published())
            import(published(status = "CANCELLED"))
            databaseClient
                .sql("UPDATE events.event SET event_date = :past, end_date = :past")
                .bind("past", LocalDate.now(BERLIN).minusDays(2))
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()

            import(published(eventDate = date.plusDays(1), startTime = LocalTime.of(20, 0)).copy(sourceId = "festsaal:other-night"))

            changes().shouldBeEmpty()
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

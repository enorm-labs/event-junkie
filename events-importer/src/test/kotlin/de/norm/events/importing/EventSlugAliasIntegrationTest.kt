package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.event.EventArtistRequest
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.event.EventRequest
import de.norm.events.event.EventResponse
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

/**
 * `event_slug_alias` against a real PostgreSQL (#5560): a retitle by an import or an admin edit keeps
 * the old slug for the same event, and a live slug always wins over an alias.
 */
class EventSlugAliasIntegrationTest : BaseControllerTest() {
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
    private val venueSlug = "uber-arena"
    private val date: LocalDate = LocalDate.now(BERLIN).plusDays(7)

    @BeforeEach
    fun setUpFixtures() {
        runBlocking {
            venueId = requireNotNull(venueRepository.save(VenueEntity(name = "Uber Arena", slug = venueSlug)).id)
            eventSourceId =
                requireNotNull(
                    eventSourceRepository
                        .save(
                            EventSourceEntity(
                                venueId = venueId,
                                name = "Uber Arena",
                                slug = venueSlug,
                                url = "https://www.uber-arena.de/",
                                sourceType = "UBER_ARENA",
                                enabled = true
                            )
                        ).id
                )
        }
    }

    private fun scraped(
        title: String,
        sourceId: String = "uber:2962"
    ) = ScrapedEvent(
        title = title,
        eventType = "CONCERT",
        eventDate = date,
        sourceId = sourceId,
        sourceUrl = "https://www.uber-arena.de/events/$sourceId"
    )

    private suspend fun importAll(vararg events: ScrapedEvent) = eventUpsertService.upsertAndCleanup(events.toList(), venueId, venueSlug, eventSourceId)

    private suspend fun stored(sourceId: String = "uber:2962"): EventEntity = eventRepository.findBySourceIdIn(listOf(sourceId)).toList().single()

    private suspend fun aliases(): Map<String, Long> =
        databaseClient
            .sql("SELECT slug, event_id FROM events.event_slug_alias")
            .map { row -> requireNotNull(row.get("slug", String::class.java)) to requireNotNull(row.get("event_id", Long::class.javaObjectType)) }
            .all()
            .collectList()
            .awaitFirstOrNull()
            .orEmpty()
            .toMap()

    @Test
    fun `an import that retitles an event keeps each former slug for the same id`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            val first = stored()
            importAll(scraped("Konstantinos Argiros"))
            val second = stored()
            importAll(scraped("Konstantinos Argiros Live"))

            second.id shouldBe first.id
            stored().id shouldBe first.id
            aliases() shouldBe mapOf(first.slug to first.id, second.slug to first.id)
        }

    @Test
    fun `an unchanged import keeps no alias`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            importAll(scraped("Wird"))

            aliases().shouldBeEmpty()
        }

    @Test
    fun `an event that takes a former slug as its live slug removes the alias`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            val oldSlug = stored().slug
            importAll(scraped("Konstantinos Argiros"))
            aliases().keys shouldBe setOf(oldSlug)

            importAll(scraped("Konstantinos Argiros"), scraped("Wird", sourceId = "uber:3001"))

            stored("uber:3001").slug shouldBe oldSlug
            aliases().shouldBeEmpty()
        }

    @Test
    fun `a retitle back to a former slug takes it back and keeps the one it left`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            val first = stored()
            importAll(scraped("Konstantinos Argiros"))
            val second = stored()
            importAll(scraped("Wird"))

            stored().slug shouldBe first.slug
            aliases() shouldBe mapOf(second.slug to first.id)
        }

    @Test
    fun `a deleted event takes its aliases with it`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            importAll(scraped("Konstantinos Argiros"))
            eventRepository.deleteById(requireNotNull(stored().id))

            aliases().shouldBeEmpty()
        }

    @Test
    fun `an admin edit that changes the title keeps the old slug`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            val before = stored()
            val id = requireNotNull(before.id)
            val edited = put(id, get(id).toRequest().copy(title = "Konstantinos Argiros"))

            edited.slug shouldBe "$date-$venueSlug-konstantinos-argiros"
            aliases() shouldBe mapOf(before.slug to id)
        }

    @Test
    fun `an admin edit back to a former slug takes it back`(): Unit =
        runBlocking {
            importAll(scraped("Wird"))
            val before = stored()
            val id = requireNotNull(before.id)
            put(id, get(id).toRequest().copy(title = "Konstantinos Argiros"))
            val back = put(id, get(id).toRequest().copy(title = "Wird"))

            back.slug shouldBe before.slug
            aliases() shouldBe mapOf("$date-$venueSlug-konstantinos-argiros" to id)
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

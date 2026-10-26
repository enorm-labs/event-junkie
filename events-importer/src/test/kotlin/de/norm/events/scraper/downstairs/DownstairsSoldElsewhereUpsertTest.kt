package de.norm.events.scraper.downstairs

import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.importing.AssociationSyncService
import de.norm.events.importing.EventUpsertService
import de.norm.events.importing.PerformerTyping
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * A Downstairs performance whose shop page redirects to another seller, through the importer and the
 * upsert against its stored row. The row kept the text it stored before the redirect (#3969).
 */
class DownstairsSoldElsewhereUpsertTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC)
    private val importer = DownstairsWebsiteImporter(htmlFetcher)
    private val upsert =
        EventUpsertService(
            eventRepository,
            mockk<AssociationSyncService>(relaxed = true),
            clock,
            PerformerTyping(mockk(), eventRepository),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

    private val sourceUrl = "https://www.downstairscomedy.shop/catalog/tickets?limit=100&use_sold=true&use_pagy=true&view_type=list"
    private val eventSourceId = 1L
    private val storedImage = "https://www.downstairscomedy.shop/uploads/unfallkind.jpg"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/downstairs/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    /** fienta's JSON-LD: one line of plain spaces, with an address and a phone number. */
    private val sellerPage =
        """
        <script type="application/ld+json">{"@type":"Event","name":"Unfallkind (Berlin)","description":
          "Die neue Stand-up-Comedy Show. Bei Fragen: wir@example.de Whatsapp: 0151 23456789 ALLE SHOWS findest du hier: www.example.de",
          "image":["https://fienta.com/cf/img/?file=x.jpg"],"offers":[{"@type":"Offer","price":"0.00"}]}</script>
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchTurboFrame(sourceUrl, "tickets") } returns loadDocument("downstairs-tickets-page1.html", sourceUrl)
        coEvery { htmlFetcher.fetchTurboFrame("$sourceUrl&page=2", "tickets") } returns
            loadDocument("downstairs-tickets-page2.html", "$sourceUrl&page=2")
        coEvery { htmlFetcher.fetchDocument(match { "/catalog/tickets/" in it }) } answers { loadDocument("downstairs-ticket.html", firstArg()) }
        coEvery { eventRepository.findBySlugIn(any()) } returns emptyFlow()
        coEvery { eventRepository.findByEventSourceIdAndEventDateBetween(any(), any(), any()) } returns emptyFlow()
    }

    /** The row as an earlier run stored it: lines run together, with non-breaking spaces. */
    private fun stored(row: ScrapedEvent) =
        EventEntity(
            id = 7L,
            venueId = 10L,
            title = row.title,
            slug = "stored-slug",
            eventDate = row.eventDate,
            startTime = row.startTime,
            imageUrl = storedImage,
            description = "Die neue Stand-up-Comedy Show. Bei Fragen: Whatsapp: 0151 234567890151 23456789ALLE SHOWS",
            pricePresale = BigDecimal("22.00"),
            doorsTime = LocalTime.of(17, 30),
            sourceId = row.sourceId,
            eventSourceId = eventSourceId,
            eventType = "COMEDY"
        )

    @Test
    fun `a show sold elsewhere replaces its stored text and keeps its stored image, doors and price`() =
        runTest {
            val row =
                importer
                    .importEvents(sourceUrl)
                    .shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .first()
            val stored = stored(row)
            coEvery { eventRepository.findBySourceIdIn(any()) } returns listOf(stored).asFlow()
            coEvery { htmlFetcher.fetchDocument(row.sourceUrl) } returns Jsoup.parse(sellerPage, "https://fienta.com/de/unfallkind-berlin-2027")

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()
            val saved = slot<Iterable<EventEntity>>()
            coEvery { eventRepository.saveAll(capture(saved)) } answers { firstArg<Iterable<EventEntity>>().asFlow() }
            upsert.upsertAndCleanup(result.events, 10L, "downstairs-comedy-club", eventSourceId)

            val written = saved.captured.single { it.sourceId == row.sourceId }
            written.id shouldBe 7L
            written.description shouldBe "Die neue Stand-up-Comedy Show. Bei Fragen: […] Whatsapp: […] ALLE SHOWS findest du hier: www.example.de"
            written.imageUrl shouldBe storedImage
            written.doorsTime shouldBe LocalTime.of(17, 30)
            written.pricePresale shouldBe BigDecimal("22.00")
        }
}

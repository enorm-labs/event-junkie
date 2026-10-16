package de.norm.events.scraper.tipiamkanzleramt

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [TipiAmKanzleramtWebsiteImporter]: the first calendar batch (34 dates, 14 shows), an empty
 * later batch, and three show pages. The other show pages answer empty.
 */
class TipiAmKanzleramtWebsiteImporterTest {
    private lateinit var importer: TipiAmKanzleramtWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val sourceUrl = "https://www.tipi-am-kanzleramt.de/de/programm/kalender.html"

    private val batchUrl by lazy {
        Jsoup.parse(loadFixture("tipiamkanzleramt-overview.html"), sourceUrl).selectFirst("[data-render-partial-url]")!!.absUrl("data-render-partial-url")
    }

    @BeforeEach
    fun setUp() {
        importer = TipiAmKanzleramtWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(loadFixture("tipiamkanzleramt-overview.html"), sourceUrl),
                etag = "\"tipi-1\"",
                lastModified = "Fri, 16 Oct 2026 10:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(match { "programmuebersicht" in it || "programme-overview" in it }) } returns
            Jsoup.parse("<html><body></body></html>", sourceUrl)
        coEvery { htmlFetcher.fetchDocument(MEHRLING_URL) } returns Jsoup.parse(loadFixture("tipiamkanzleramt-show-mehrling.html"), MEHRLING_URL)
        coEvery { htmlFetcher.fetchDocument(KORNEEV_URL) } returns Jsoup.parse(loadFixture("tipiamkanzleramt-show-korneev.html"), KORNEEV_URL)
        coEvery { htmlFetcher.fetchDocument(THALBACH_URL) } returns Jsoup.parse(loadFixture("tipiamkanzleramt-show-thalbach.html"), THALBACH_URL)
        coEvery { htmlFetcher.fetchDocument(match { it.startsWith(batchUrl) }) } returns
            Jsoup.parse(loadFixture("tipiamkanzleramt-calendar-batch-empty.html"), batchUrl)
    }

    private fun loadFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/tipiamkanzleramt/$name")!!
            .bufferedReader()
            .readText()

    private suspend fun importedEvents() = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>().events

    @Test
    fun `importEvents extracts every performance date of the first batch`() =
        runTest {
            val events = importedEvents()

            events shouldHaveSize 34
            events.first().eventDate shouldBe LocalDate.of(2026, 10, 16)
            events.last().eventDate shouldBe LocalDate.of(2026, 11, 29)
        }

    @Test
    fun `importEvents maps the calendar fields under the venue's own prefix`() =
        runTest {
            val event = importedEvents().first()

            event.title shouldBe "Katharine Mehrling & Band"
            event.subtitle shouldBe "DIAMONDS"
            event.startTime shouldBe LocalTime.of(20, 0)
            event.sourceUrl shouldBe MEHRLING_URL
            event.sourceId shouldBe "tipi_am_kanzleramt:2026-10-16-katharine-mehrling-band-diamonds"
            event.ticketUrl shouldBe "https://tickets.tipi-am-kanzleramt.de/webshop/webticket/shop?event=4473&language=de"
            event.imageUrl.shouldNotBeNull() shouldStartWith "https://www.tipi-am-kanzleramt.de/_Resources/Persistent/"
        }

    @Test
    fun `importEvents fetches each distinct show page once`() =
        runTest {
            importedEvents()

            coVerify(exactly = 1) { htmlFetcher.fetchDocument(MEHRLING_URL) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(KORNEEV_URL) }
        }

    @Test
    fun `importEvents types a Musik-Show as SHOW with the show page's genre and price`() =
        runTest {
            val show = importedEvents().first { it.sourceUrl == MEHRLING_URL }

            show.eventType shouldBe "SHOW"
            show.genre shouldBe "Musik-Show"
            show.pricePresale shouldBe BigDecimal("27.90")
            show.priceNote shouldBe "Ab 27,90 € bis 62,90 €"
            show.description.shouldNotBeNull() shouldContain "Marilyn Monroe"
            show.artists.shouldBeEmpty()
        }

    @Test
    fun `importEvents types a Chanson night as CONCERT`() =
        runTest {
            val concert = importedEvents().first { it.sourceUrl == KORNEEV_URL }

            concert.eventType shouldBe "CONCERT"
            concert.genre shouldBe "Chanson"
        }

    @Test
    fun `importEvents types a Lesung as READING`() =
        runTest {
            val reading = importedEvents().single { it.sourceUrl == THALBACH_URL }

            reading.eventType shouldBe "READING"
            reading.genre shouldBe "Lesung"
        }

    @Test
    fun `importEvents marks the sold-out night from its JSON-LD availability`() =
        runTest {
            val preis = importedEvents().single { it.title == "Deutscher Musical Theater Preis" }

            preis.soldOut shouldBe true
            importedEvents().count { it.soldOut } shouldBe 1
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.etag shouldBe "\"tipi-1\""
            result.lastModified shouldBe "Fri, 16 Oct 2026 10:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the calendar is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns an empty list for a calendar without cards`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            importedEvents().shouldBeEmpty()
        }

    @Test
    fun `importEvents keeps the calendar data and flags every date when the show pages fail`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(match { "programmuebersicht" in it }) } throws RuntimeException("Network error")

            val events = importedEvents()

            events shouldHaveSize 34
            events.forEach { it.detailUnavailable shouldBe true }
            events.first().genre shouldBe null
        }

    @Test
    fun `importEvents stops the batch walk at the first empty batch`() =
        runTest {
            importedEvents()

            coVerify(exactly = 1) { htmlFetcher.fetchDocument("$batchUrl&currentPage=2") }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("$batchUrl&currentPage=3") }
        }

    @Test
    fun `importEvents reads a later batch, and a card whose phone-booking modal precedes its JSON-LD`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument("$batchUrl&currentPage=2") } returns
                Jsoup.parse(loadFixture("tipiamkanzleramt-calendar-batch-2.html"), "$batchUrl&currentPage=2")

            val events = importedEvents()

            events shouldHaveSize 34 + 42
            val gala = events.single { it.title == "Silvestergala" }
            gala.eventDate shouldBe LocalDate.of(2026, 12, 31)
            gala.startTime shouldBe LocalTime.of(18, 0)
            gala.subtitle shouldBe "mit Sven Ratzke & Band"
            gala.ticketUrl shouldBe null
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.TIPI_AM_KANZLERAMT
    }

    private companion object {
        const val SHOW_BASE = "https://www.tipi-am-kanzleramt.de/de/programm/programmuebersicht"
        const val MEHRLING_URL = "$SHOW_BASE/katharine-mehrling-band-diamonds.html"
        const val KORNEEV_URL = "$SHOW_BASE/vladimir-korneev-dalida.html"
        const val THALBACH_URL = "$SHOW_BASE/literatur-live-katharina-thalbach-miss-merkel.html"
    }
}

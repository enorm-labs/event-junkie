package de.norm.events.scraper.colosseum

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Unit tests for [ColosseumWebsiteImporter].
 */
class ColosseumWebsiteImporterTest {
    private lateinit var importer: ColosseumWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.colosseumberlin.com/event"

    @BeforeEach
    fun setUp() {
        importer = ColosseumWebsiteImporter(htmlFetcher)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/colosseum/colosseum-overview.html")!!
                .bufferedReader()
                .readText()
        val document = Jsoup.parse(html, sourceUrl)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = document,
                etag = "W/\"6f1a0d2b8c3e4a5d9f0b1c2d3e4f5a6b\"",
                lastModified = null
            )
        // No event page answers unless a test says otherwise, so each row keeps its listing record.
        coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(statusCode = 404, url = sourceUrl)
    }

    private fun detailPage(fixture: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/colosseum/$fixture")!!
                .bufferedReader()
                .readText(),
            sourceUrl
        )

    private fun eventPage(slug: String) = "https://www.colosseumberlin.com/details-registrierung/$slug"

    @Test
    fun `takes the times and the ticket's own price from the event page and keeps the listing's link`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(eventPage("investment")) } returns detailPage("colosseum-detail-ticket-price.html")
            coEvery { htmlFetcher.fetchDocument(eventPage("gysis-begegnungen-mit-felix-jaehn")) } returns
                detailPage("colosseum-detail-gysi.html")

            val events = (importer.importEvents(sourceUrl) as ImportResult.Success).events.associateBy { it.sourceId }

            val investment = events.getValue("colosseum:investment")
            investment.pricePresale shouldBe BigDecimal("16.50")
            investment.priceNote.shouldBeNull()
            investment.ticketUrl shouldBe eventPage("investment")
            val gysi = events.getValue("colosseum:gysis-begegnungen-mit-felix-jaehn")
            gysi.doorsTime shouldBe LocalTime.of(11, 0)
            gysi.startTime shouldBe LocalTime.of(11, 30)
            // The page sells no ticket itself, so the listing's price and link stand.
            gysi.ticketUrl shouldBe eventPage("gysis-begegnungen-mit-felix-jaehn")
        }

    @Test
    fun `keeps the listing's record when the event page fails`() =
        runTest {
            val kaeser =
                (importer.importEvents(sourceUrl) as ImportResult.Success)
                    .events
                    .single { it.sourceId == "colosseum:gysis-begegnungen-mit-joe-kaeser" }

            kaeser.doorsTime.shouldBeNull()
            kaeser.ticketUrl shouldBe eventPage("gysis-begegnungen-mit-joe-kaeser")
        }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 18
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "W/\"6f1a0d2b8c3e4a5d9f0b1c2d3e4f5a6b\""
            result.lastModified shouldBe null
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for page without a warmup payload`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><main data-hook='EVENTS_ROOT_NODE'></main></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.COLOSSEUM
    }
}

package de.norm.events.scraper.marmorbar

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedField
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Unit tests for [MarmorbarWebsiteImporter]. */
class MarmorbarWebsiteImporterTest {
    private lateinit var importer: MarmorbarWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.marmorbar.com/en/event-list"

    private fun page(fixture: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/marmorbar/$fixture")!!
                .bufferedReader()
                .readText(),
            sourceUrl
        )

    private fun eventPage(slug: String) = "https://www.marmorbar.com/en/event-details/$slug"

    @BeforeEach
    fun setUp() {
        importer = MarmorbarWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = page("marmorbar-overview.html"), etag = "W/\"marmorbar\"", lastModified = null)
        coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(statusCode = 404, url = sourceUrl)
        coEvery { htmlFetcher.fetchDocument(eventPage("kylie-wears-berghaus")) } returns page("marmorbar-detail-kylie.html")
    }

    @Test
    fun `merges the event page's text and price into the listing's record`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 5
            result.etag shouldBe "W/\"marmorbar\""
            val kylie = result.events.single { it.title == "KYLIE WEARS BERGHAUS" }
            kylie.pricePresale shouldBe BigDecimal("5.00")
            kylie.priceNote shouldBe "€5.00 – €8.00"
            kylie.description.shouldNotBeNull() shouldStartWith "After multiple sold out shows"
            kylie.ticketUrl shouldBe eventPage("kylie-wears-berghaus")
            kylie.detailUnavailable shouldBe false
        }

    @Test
    fun `a failed event page keeps the listing's record and the stored text and prices`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val hauswerks = result.events.single { it.title == "hauswerks - afterglow" }
            hauswerks.pricePresale.shouldBeNull()
            hauswerks.detailUnavailable shouldBe true
            hauswerks.detailPageOwns shouldBe setOf(ScrapedField.DESCRIPTION, ScrapedField.PRICES)
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty page yields no events`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource is MARMORBAR`() {
        importer.eventSource shouldBe EventSource.MARMORBAR
    }
}

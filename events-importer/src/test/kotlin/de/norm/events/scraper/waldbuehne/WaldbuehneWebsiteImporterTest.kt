package de.norm.events.scraper.waldbuehne

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WaldbuehneWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = WaldbuehneWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.waldbuehne-berlin.de/programm-und-tickets/"
    private val blink = "https://www.waldbuehne-berlin.de/event/blink-182_2027-06-08_00/"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/waldbuehne/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
            FetchResult.Success(document = loadDocument("waldbuehne-programm.html", sourceUrl), etag = "\"v1\"", lastModified = null)
        coEvery { htmlFetcher.fetchDocument(any()) } returns loadDocument("waldbuehne-event-no-presale.html", sourceUrl)
        coEvery { htmlFetcher.fetchDocument(blink) } returns loadDocument("waldbuehne-event-support.html", blink)
    }

    @Test
    fun `reads the listing and each event page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 20
            result.etag shouldBe "\"v1\""
            result.events
                .first { it.title == "blink-182" }
                .artists
                .map { it.name } shouldBe listOf("blink-182", "Billy Talent")
        }

    @Test
    fun `flags a row whose event page fails and keeps the rest`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(blink) } throws IllegalStateException("boom")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 20
            result.events.first { it.title == "blink-182" }.detailUnavailable shouldBe true
        }

    @Test
    fun `returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.WALDBUEHNE
    }
}

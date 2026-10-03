package de.norm.events.scraper.thewall

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TheWallWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = TheWallWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://thewallcomedy.com/venues/thewallcomedy/events/"
    private val partials = "https://thewallcomedy.com/venues/a6448e5e-ee32-46be-afaa-a47895adc48a/partials/events/"

    // Page 2's snapshot links page 3; the last page's snapshot stands in for it.
    private val page2Url = "$partials?page=2"
    private val page3Url = "$partials?page=3"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/thewall/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = loadDocument("thewall-events.html", sourceUrl), etag = null, lastModified = null)
        coEvery { htmlFetcher.fetchDocument(page2Url) } returns loadDocument("thewall-events-page-2.html", page2Url)
        coEvery { htmlFetcher.fetchDocument(page3Url) } returns loadDocument("thewall-events-page-last.html", page3Url)
    }

    @Test
    fun `follows the Show more partials to the last page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 27
            result.complete shouldBe true
            result.events.last().eventDate shouldBe LocalDate.of(2027, 1, 30)
            coVerify(exactly = 2) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps the first page when a partial fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws HttpFetchException(503, page2Url)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 12
            result.complete shouldBe false
        }

    @Test
    fun `returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.THE_WALL
    }
}

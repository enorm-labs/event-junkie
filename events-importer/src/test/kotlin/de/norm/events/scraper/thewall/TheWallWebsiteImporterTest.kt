package de.norm.events.scraper.thewall

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    @BeforeEach
    fun setUp() {
        stubFetcher(
            sourceUrl to "thewall/thewall-events.html",
            page2Url to "thewall/thewall-events-page-2.html",
            page3Url to "thewall/thewall-events-page-last.html",
            fetcher = htmlFetcher
        )
        // Every event page answers with the one saved page.
        coEvery { htmlFetcher.fetchDocument(match { EVENT_PAGE in it }) } answers { fixtureDocument("thewall/thewall-event.html", firstArg()) }
    }

    @Test
    fun `follows the Show more partials to the last page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 27
            result.complete shouldBe true
            result.events.last().eventDate shouldBe LocalDate.of(2027, 1, 30)
            coVerify(exactly = 2) { htmlFetcher.fetchDocument(match { EVENT_PAGE !in it }) }
        }

    @Test
    fun `reads one event page per distinct description and takes its paragraphs`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // 27 events share 8 descriptions.
            coVerify(exactly = 8) { htmlFetcher.fetchDocument(match { EVENT_PAGE in it }) }
            result.events.forEach { it.description.shouldNotBeNull() shouldContain "\n\nJoin us for a night of laughter" }
        }

    @Test
    fun `a failed event page flags its rows and keeps the listing's text`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(match { EVENT_PAGE in it }) } throws HttpFetchException(503, "event page")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 27
            result.events.all { it.detailUnavailable } shouldBe true
            result.events.all { it.description?.contains('\n') != true } shouldBe true
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

    private companion object {
        const val EVENT_PAGE = "thewallcomedy.com/events/"
    }
}

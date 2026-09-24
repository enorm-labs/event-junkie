package de.norm.events.scraper.schokoladen

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

/**
 * Unit tests for [SchokoladenWebsiteImporter].
 */
class SchokoladenWebsiteImporterTest {
    private lateinit var importer: SchokoladenWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.schokoladen-mitte.de/"

    // Page 1 links `?page=2`, whose snapshot links `?page=3`; the last page's snapshot stands in for page 3.
    private val page2Url = "$sourceUrl?page=2"
    private val page3Url = "$sourceUrl?page=3"

    private fun loadDocument(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/schokoladen/$name")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        importer = SchokoladenWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = loadDocument("schokoladen-overview.html", sourceUrl),
                etag = "\"choc123\"",
                lastModified = "Sat, 11 Jul 2026 10:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(page2Url) } returns loadDocument("schokoladen-overview-page-2.html", page2Url)
        coEvery { htmlFetcher.fetchDocument(page3Url) } returns loadDocument("schokoladen-overview-page-last.html", page3Url)
    }

    @Test
    fun `importEvents reads every listing page to the last`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 28
            result.events.first().eventDate shouldBe LocalDate.of(2026, 7, 11)
            result.events.last().eventDate shouldBe LocalDate.of(2027, 5, 16)
            coVerify(exactly = 2) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `importEvents keeps the first page when a later page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws HttpFetchException(503, page2Url)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 10
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"choc123\""
            result.lastModified shouldBe "Sat, 11 Jul 2026 10:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when page unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns empty list for page without events`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='main'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SCHOKOLADEN
    }
}

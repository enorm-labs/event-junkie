package de.norm.events.scraper.gretchen

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

/**
 * Unit tests for [GretchenWebsiteImporter].
 */
class GretchenWebsiteImporterTest {
    private lateinit var importer: GretchenWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.gretchen-club.de/"

    @BeforeEach
    fun setUp() {
        importer = GretchenWebsiteImporter(htmlFetcher)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/gretchen/gretchen-overview.html")!!
                .bufferedReader()
                .readText()
        val document = Jsoup.parse(html, sourceUrl)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = document,
                etag = "\"abc123\"",
                lastModified = "Fri, 10 Jul 2026 03:00:00 GMT"
            )
    }

    @Test
    fun `importEvents extracts all events from fixture`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 92
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"abc123\""
            result.lastModified shouldBe "Fri, 10 Jul 2026 03:00:00 GMT"
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
            val emptyDoc = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.GRETCHEN
    }
}

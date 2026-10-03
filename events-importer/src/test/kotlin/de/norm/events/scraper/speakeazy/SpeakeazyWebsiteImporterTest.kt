package de.norm.events.scraper.speakeazy

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

class SpeakeazyWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = SpeakeazyWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.speakeazyberlin.de/events"

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/speakeazy/speakeazy-events.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, sourceUrl), etag = "\"abc\"", lastModified = "Sat, 03 Oct 2026 10:00:00 GMT")
    }

    @Test
    fun `imports the upcoming nights with the page's validators`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
            result.etag shouldBe "\"abc\""
            result.lastModified shouldBe "Sat, 03 Oct 2026 10:00:00 GMT"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without a listing`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", sourceUrl), etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.SPEAKEAZY
        importer.listsWholeProgramme shouldBe true
    }
}

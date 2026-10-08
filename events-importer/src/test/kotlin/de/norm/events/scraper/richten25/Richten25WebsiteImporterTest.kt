package de.norm.events.scraper.richten25

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
import org.junit.jupiter.api.Test

class Richten25WebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = Richten25WebsiteImporter(htmlFetcher)
    private val url = "https://richten25.de/events"

    @Test
    fun `imports the programme with the page's validators`() =
        runTest {
            val html = javaClass.classLoader.getResourceAsStream("scraper/richten25/richten25-events.html")!!
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(html, null, url), etag = "\"richten25\"", lastModified = "Thu, 08 Oct 2026 06:00:00 GMT")

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
            result.etag shouldBe "\"richten25\""
            result.lastModified shouldBe "Thu, 08 Oct 2026 06:00:00 GMT"
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.RICHTEN25
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe false
    }
}

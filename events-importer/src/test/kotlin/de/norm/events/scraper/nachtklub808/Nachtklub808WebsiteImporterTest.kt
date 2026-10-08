package de.norm.events.scraper.nachtklub808

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

class Nachtklub808WebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = Nachtklub808WebsiteImporter(htmlFetcher)
    private val url = "https://808.berlin/"

    @Test
    fun `imports the programme with the page's validators`() =
        runTest {
            val html = javaClass.classLoader.getResourceAsStream("scraper/nachtklub808/nachtklub808-overview.html")!!
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(html, null, url), etag = "\"nachtklub808\"", lastModified = "Thu, 08 Oct 2026 06:00:00 GMT")

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 24
            result.etag shouldBe "\"nachtklub808\""
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
        importer.eventSource shouldBe EventSource.NACHTKLUB_808
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe false
    }
}

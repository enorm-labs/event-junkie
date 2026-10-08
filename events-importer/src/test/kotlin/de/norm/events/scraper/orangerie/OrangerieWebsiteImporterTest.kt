package de.norm.events.scraper.orangerie

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

class OrangerieWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = OrangerieWebsiteImporter(htmlFetcher)
    private val url = "https://www.orangerie-nk.de/?lang=de"

    @Test
    fun `imports the programme with the page's validators`() =
        runTest {
            val html = javaClass.classLoader.getResourceAsStream("scraper/orangerie/orangerie-home.html")!!
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse(html, null, url), etag = "\"orangerie\"", lastModified = "Thu, 08 Oct 2026 06:00:00 GMT")

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 16
            result.etag shouldBe "\"orangerie\""
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
        importer.eventSource shouldBe EventSource.ORANGERIE_NEUKOELLN
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe false
    }
}

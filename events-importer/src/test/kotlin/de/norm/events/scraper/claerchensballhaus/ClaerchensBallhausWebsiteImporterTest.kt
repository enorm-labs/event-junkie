package de.norm.events.scraper.claerchensballhaus

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.PLAIN_SCRAPER_USER_AGENT
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class ClaerchensBallhausWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ClaerchensBallhausWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://claerchensball.haus/programm/"

    private fun stub(result: FetchResult) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any(), any()) } returns result
    }

    @Test
    fun `reads the calendar under the plain User-Agent the WAF lets through, with the page's validators`() =
        runTest {
            stub(FetchResult.Success(claerchensCalendar(), "\"v1\"", "Fri, 23 Oct 2026 08:00:00 GMT"))

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 97
            result.etag shouldBe "\"v1\""
            result.lastModified shouldBe "Fri, 23 Oct 2026 08:00:00 GMT"
            coVerify { htmlFetcher.fetch(sourceUrl, null, null, emptyMap(), PLAIN_SCRAPER_USER_AGENT) }
        }

    @Test
    fun `passes a 304 through`() =
        runTest {
            stub(FetchResult.NotModified)

            importer.importEvents(sourceUrl, "\"v1\"", null).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for a page without the calendar`() =
        runTest {
            stub(FetchResult.Success(Jsoup.parse("<html><body></body></html>", sourceUrl), null, null))

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `is the Clärchens Ballhaus source and lists the whole programme`() {
        importer.eventSource shouldBe EventSource.CLAERCHENS_BALLHAUS
        importer.listsWholeProgramme shouldBe true
    }
}

package de.norm.events.scraper.punchline

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PunchlineWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = PunchlineWebsiteImporter(htmlFetcher)
    private val url = "https://punchlineberlin.com/de/tickets"

    @Test
    fun `imports the dates with the page's validators`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(fixtureDocument("punchline/punchline-tickets.html", url), "\"pl\"", null)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 27
            result.etag shouldBe "\"pl\""
        }

    @Test
    fun `returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.PUNCHLINE
    }
}

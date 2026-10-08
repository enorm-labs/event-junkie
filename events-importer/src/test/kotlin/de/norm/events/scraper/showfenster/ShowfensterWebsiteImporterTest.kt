package de.norm.events.scraper.showfenster

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

class ShowfensterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "https://www.showfenster-show.de/%C3%BCbersichtskalender"
    private val importer = ShowfensterWebsiteImporter(htmlFetcher)

    private fun serve(html: String) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, sourceUrl), etag = "\"e1\"", lastModified = null)
    }

    @Test
    fun `imports the fixture and passes the validator on`() =
        runTest {
            serve(
                javaClass.classLoader
                    .getResourceAsStream("scraper/showfenster/showfenster-overview.html")!!
                    .bufferedReader()
                    .readText()
            )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 46
            result.etag shouldBe "\"e1\""
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `a page without the Wix payload imports nothing`() =
        runTest {
            serve("<html><body><div id=\"calendar\"></div></body></html>")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource is SHOWFENSTER`() {
        importer.eventSource shouldBe EventSource.SHOWFENSTER
    }
}

package de.norm.events.scraper.wienerblut

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class WienerBlutWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = WienerBlutWebsiteImporter(htmlFetcher)
    private val sourceUrl = "https://www.wienerblut.org/event-list"

    private fun stub(result: FetchResult) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any(), any()) } returns result
    }

    @Test
    fun `imports the listed gig with the page's validators`() =
        runTest {
            val html =
                javaClass.classLoader
                    .getResourceAsStream("scraper/wienerblut/wienerblut-overview.html")!!
                    .bufferedReader()
                    .readText()
            stub(FetchResult.Success(Jsoup.parse(html, sourceUrl), "W/\"wix\"", null))

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 1
            result.etag shouldBe "W/\"wix\""
        }

    @Test
    fun `passes a 304 through`() =
        runTest {
            stub(FetchResult.NotModified)

            importer.importEvents(sourceUrl, "W/\"wix\"", null).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty page`() =
        runTest {
            stub(FetchResult.Success(Jsoup.parse("<html><body></body></html>", sourceUrl), null, null))

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `is the Wiener Blut source and lists the whole programme`() {
        importer.eventSource shouldBe EventSource.WIENER_BLUT
        importer.listsWholeProgramme shouldBe true
    }
}

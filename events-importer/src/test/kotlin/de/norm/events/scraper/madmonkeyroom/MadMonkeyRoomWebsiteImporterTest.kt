package de.norm.events.scraper.madmonkeyroom

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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

class MadMonkeyRoomWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = MadMonkeyRoomWebsiteImporter(htmlFetcher)
    private val url = "https://t.rausgegangen.de/tickets/shop/mad-monkey-2"

    @Test
    fun `imports the shop list with its validators and reads no other page`() =
        runTest {
            val html =
                javaClass.classLoader
                    .getResourceAsStream("scraper/madmonkeyroom/madmonkeyroom-shop.html")!!
                    .bufferedReader()
                    .readText()
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.Success(Jsoup.parse(html, url), "\"mm\"", "Fri, 16 Oct 2026")

            val result = importer.importEvents(url).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 460
            result.etag shouldBe "\"mm\""
            result.lastModified shouldBe "Fri, 16 Oct 2026"
            result.events.all { it.sourceId.startsWith(EventSource.MAD_MONKEY_ROOM.sourceIdPrefix) } shouldBe true
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `returns NotModified when the shop is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `returns no events for an empty shop`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.Success(Jsoup.parse("<body></body>", url), null, null)

            importer
                .importEvents(url)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `eventSource and whole programme match the shop`() {
        importer.eventSource shouldBe EventSource.MAD_MONKEY_ROOM
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe false
    }
}

package de.norm.events.scraper.zku

import de.norm.events.event.EventType
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

class ZkuWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ZkuWebsiteImporter(htmlFetcher)
    private val url = "https://www.zku-berlin.org/"

    private fun fixture(name: String): String = javaClass.classLoader.getResource("scraper/zku/$name")!!.readText()

    private fun stubCalendar(html: String = fixture("zku-home.html")) {
        coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, url), etag = "\"zku\"", lastModified = "Sat, 17 Oct 2026 03:00:00 GMT")
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ZKU
    }

    @Test
    fun `returns NotModified when the calendar is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified
            importer.importEvents(url) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the calendar, reads each entry's page and propagates validators`() =
        runTest {
            stubCalendar()
            coEvery { htmlFetcher.fetchDocument(any()) } answers {
                val pageUrl = firstArg<String>()
                val name = if ("unruh-friends" in pageUrl) "zku-event-unruh-friends.html" else "zku-event-diffusion-a-meddling.html"
                Jsoup.parse(fixture(name), pageUrl)
            }

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
            result.etag shouldBe "\"zku\""
            result.lastModified shouldBe "Sat, 17 Oct 2026 03:00:00 GMT"
            val unruh = result.events.single { it.title == "UNRUH & friends" }
            unruh.eventType shouldBe EventType.PARTY.name
            unruh.artists shouldHaveSize 4
            coVerify(exactly = 9) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps the calendar row when an entry's page fails`() =
        runTest {
            stubCalendar()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(url) as ImportResult.Success

            result.events shouldHaveSize 9
            result.events.all { it.detailUnavailable } shouldBe true
        }

    @Test
    fun `an empty calendar imports nothing`() =
        runTest {
            stubCalendar("<html><body></body></html>")

            (importer.importEvents(url) as ImportResult.Success).events.shouldBeEmpty()
        }
}

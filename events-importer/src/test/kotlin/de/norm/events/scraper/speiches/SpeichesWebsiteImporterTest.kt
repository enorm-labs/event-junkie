package de.norm.events.scraper.speiches

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SpeichesWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val sourceUrl = "http://www.rockradio.de/rr_termine_speiche_werbung_termine_raumerstr.php"
    private val importer = SpeichesWebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC))

    private fun serve(html: String) {
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(document = Jsoup.parse(html, sourceUrl), etag = "\"e1\"", lastModified = null)
    }

    @Test
    fun `imports the fixture and passes the validator on`() =
        runTest {
            serve(
                javaClass.classLoader
                    .getResourceAsStream("scraper/speiches/speiches-overview.html")!!
                    .bufferedReader()
                    .readText()
            )

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 40
            result.etag shouldBe "\"e1\""
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `an empty table imports nothing`() =
        runTest {
            serve("<html><body><table></table></body></html>")

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource is SPEICHES`() {
        importer.eventSource shouldBe EventSource.SPEICHES
    }
}

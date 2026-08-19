package de.norm.events.scraper.wildatheart

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [WildAtHeartWebsiteImporter].
 *
 * The clock is pinned to 2026-07-10 — before the fixture's first event (15 July 2026) — so
 * weekday-based year inference stays deterministic.
 */
class WildAtHeartWebsiteImporterTest {
    private lateinit var importer: WildAtHeartWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC)
    private val sourceUrl = "https://www.wildatheartberlin.de/concerts.php"

    @BeforeEach
    fun setUp() {
        importer = WildAtHeartWebsiteImporter(htmlFetcher, clock)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/wildatheart/wildatheart-concerts.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, sourceUrl),
                etag = "\"wah-etag\"",
                lastModified = "Mon, 06 Jul 2026 08:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.WILD_AT_HEART
    }

    @Test
    fun `importEvents parses the programme and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 77
            result.events.any { it.title == "Foxy" } shouldBe true
            result.etag shouldBe "\"wah-etag\""
            result.lastModified shouldBe "Mon, 06 Jul 2026 08:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns an empty list for a page without events`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><table><tr><td>No events</td></tr></table></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}

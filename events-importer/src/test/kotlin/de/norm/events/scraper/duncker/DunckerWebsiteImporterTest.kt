package de.norm.events.scraper.duncker

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
 * Unit tests for [DunckerWebsiteImporter].
 *
 * The clock is pinned to 2026-07-01 — before the fixture's earliest date (03.07.) — so the
 * scraper's past-event cutoff keeps all of them and weekday-based year inference stays
 * deterministic.
 */
class DunckerWebsiteImporterTest {
    private lateinit var importer: DunckerWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC)
    private val sourceUrl = "https://www.dunckerclub.de/start.html"

    @BeforeEach
    fun setUp() {
        importer = DunckerWebsiteImporter(htmlFetcher, clock)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/duncker/duncker-overview.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, sourceUrl),
                etag = "\"duncker-etag\"",
                lastModified = "Thu, 02 Jul 2026 13:52:23 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.DUNCKER
    }

    @Test
    fun `importEvents parses the programme and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 13
            result.events.first().title shouldBe "Das neue Partymaß 80-90-00"
            result.etag shouldBe "\"duncker-etag\""
            result.lastModified shouldBe "Thu, 02 Jul 2026 13:52:23 GMT"
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
            val emptyDoc = Jsoup.parse("<html><body><p>No events</p></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}

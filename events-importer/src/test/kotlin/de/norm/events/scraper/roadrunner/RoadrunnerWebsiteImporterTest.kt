package de.norm.events.scraper.roadrunner

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
 * Unit tests for [RoadrunnerWebsiteImporter].
 *
 * The clock is pinned to 2026-05-01 — before the fixture's single event (29 May 2026) — so the
 * scraper's past-event cutoff keeps it and weekday-based year inference stays deterministic.
 */
class RoadrunnerWebsiteImporterTest {
    private lateinit var importer: RoadrunnerWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC)
    private val sourceUrl = "http://www.roadrunners-paradise.de/programm.html"

    @BeforeEach
    fun setUp() {
        importer = RoadrunnerWebsiteImporter(htmlFetcher, clock)
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/roadrunner/roadrunner-programm.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, sourceUrl),
                etag = "\"rr-etag\"",
                lastModified = "Mon, 06 Jul 2026 08:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ROADRUNNER
    }

    @Test
    fun `importEvents parses the programme and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 1
            result.events.single().title shouldBe "BERLIN FREAK BURLESQUE CIRCUS"
            result.etag shouldBe "\"rr-etag\""
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
            val emptyDoc = Jsoup.parse("<html><body><p>No events</p></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}

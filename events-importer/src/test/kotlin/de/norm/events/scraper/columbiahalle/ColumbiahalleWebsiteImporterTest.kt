package de.norm.events.scraper.columbiahalle

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
import java.time.LocalDate

/**
 * Unit tests for [ColumbiahalleWebsiteImporter].
 *
 * Columbiahalle is a single-page source, so the importer's job is limited to the fetch → scrape →
 * `ImportResult` path: no detail pages are requested.
 */
class ColumbiahalleWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ColumbiahalleWebsiteImporter(htmlFetcher)
    private val overviewUrl = "https://www.columbiahalle.berlin/veranstaltungen.html"

    private fun stubOverview() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/columbiahalle/columbiahalle-overview.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, overviewUrl),
                etag = "\"columbiahalle-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.COLUMBIAHALLE
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            stubOverview()
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 87
            result.etag shouldBe "\"columbiahalle-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `spans the whole published programme`() =
        runTest {
            stubOverview()
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.first().eventDate shouldBe LocalDate.of(2026, 8, 7)
            result.events.last().eventDate shouldBe LocalDate.of(2030, 12, 28)
        }

    @Test
    fun `returns no events for a page without an event list`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><main></main></body></html>", overviewUrl),
                    etag = null,
                    lastModified = null
                )
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}

package de.norm.events.scraper.tempodrom

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

/**
 * Unit tests for [TempodromWebsiteImporter].
 *
 * Tempodrom is a single-page source whose payload is JSON-LD, so the importer must fetch the
 * listing and nothing else. The server sends `Last-Modified`, so the `NotModified` path is real
 * here rather than theoretical.
 */
class TempodromWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = TempodromWebsiteImporter(htmlFetcher)
    private val listingUrl = "https://www.tempodrom.de/programm-und-tickets/"

    private fun stubListing() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/tempodrom/tempodrom-programme.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, listingUrl),
                etag = null,
                lastModified = "Sat, 01 Aug 2026 11:45:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.TEMPODROM
    }

    @Test
    fun `returns NotModified when the programme is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the whole programme and propagates Last-Modified`() =
        runTest {
            stubListing()
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 143
            result.lastModified shouldBe "Sat, 01 Aug 2026 11:45:00 GMT"
        }

    @Test
    fun `fetches nothing beyond the listing`() =
        runTest {
            stubListing()
            importer.importEvents(listingUrl)
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><main></main></body></html>", listingUrl),
                    etag = null,
                    lastModified = null
                )
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}

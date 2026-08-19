package de.norm.events.scraper.derweissehase

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
 * Unit tests for [DerWeisseHaseWebsiteImporter].
 *
 * Der Weiße Hase is a single-page source, so the importer's job is limited to the fetch → scrape →
 * `ImportResult` path: no detail pages are requested.
 */
class DerWeisseHaseWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = DerWeisseHaseWebsiteImporter(htmlFetcher)
    private val listingUrl = "https://derweissehase.club/events"

    private fun stubListing() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/derweissehase/derweissehase-overview.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, listingUrl),
                etag = "\"derweissehase-etag\"",
                lastModified = "Thu, 06 Aug 2026 10:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.DER_WEISSE_HASE
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the whole listing and propagates conditional headers`() =
        runTest {
            stubListing()
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 16
            result.events.first().eventDate shouldBe LocalDate.of(2026, 8, 6)
            result.etag shouldBe "\"derweissehase-etag\""
            result.lastModified shouldBe "Thu, 06 Aug 2026 10:00:00 GMT"
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

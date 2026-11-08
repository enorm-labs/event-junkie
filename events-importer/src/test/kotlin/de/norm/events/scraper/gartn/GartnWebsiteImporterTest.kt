package de.norm.events.scraper.gartn

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureDocument
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
 * Unit tests for [GartnWebsiteImporter].
 *
 * gART.n is a single-page source — one Carrd page with no API and no per-event pages — so the
 * importer must never request anything beyond it.
 */
class GartnWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = GartnWebsiteImporter(htmlFetcher)
    private val overviewUrl = "https://www.gartn.xyz/"

    private fun stubOverview() {
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("gartn/gartn-overview.html", overviewUrl),
                etag = "\"2a9df-65834084bdc88\"",
                lastModified = "Tue, 04 Aug 2026 07:59:20 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.GARTN
    }

    @Test
    fun `returns NotModified when the page is unchanged`() =
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
            result.events shouldHaveSize 10
            result.etag shouldBe "\"2a9df-65834084bdc88\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 07:59:20 GMT"
        }

    @Test
    fun `fetches nothing beyond the programme page`() =
        runTest {
            stubOverview()
            importer.importEvents(overviewUrl)
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><div class=\"site-main\"></div></body></html>", overviewUrl),
                    etag = null,
                    lastModified = null
                )
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}

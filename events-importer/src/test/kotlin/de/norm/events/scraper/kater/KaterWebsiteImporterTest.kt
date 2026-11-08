package de.norm.events.scraper.kater

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
 * Unit tests for [KaterWebsiteImporter].
 *
 * Uses a static HTML fixture and a mocked [HtmlFetcher]. Kater is a single-page source — neither
 * its REST route nor its `/event/<slug>` pages carry usable data — so the importer must never
 * request anything beyond the homepage.
 */
class KaterWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = KaterWebsiteImporter(htmlFetcher)
    private val overviewUrl = "https://www.katerclub.de/"

    private fun stubOverview() {
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("kater/kater-overview.html", overviewUrl),
                etag = "\"kater-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.KATER
    }

    @Test
    fun `returns NotModified when the homepage is unchanged`() =
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
            result.events shouldHaveSize 26
            result.etag shouldBe "\"kater-etag\""
            result.lastModified shouldBe "Sat, 01 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `fetches nothing beyond the homepage`() =
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
                    document = Jsoup.parse("<html><body><main></main></body></html>", overviewUrl),
                    etag = null,
                    lastModified = null
                )
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}

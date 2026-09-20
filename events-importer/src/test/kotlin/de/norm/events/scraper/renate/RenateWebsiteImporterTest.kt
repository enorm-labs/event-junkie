package de.norm.events.scraper.renate

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
 * Unit tests for [RenateWebsiteImporter].
 *
 * Renate is a single-page source — its REST API registers no `event` post type and it publishes no
 * per-event page — so the importer must never request anything beyond the homepage.
 */
class RenateWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = RenateWebsiteImporter(htmlFetcher)
    private val overviewUrl = "https://www.renate.cc/"

    private fun stubOverview() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/renate/renate-overview.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(html, overviewUrl),
                etag = "\"renate-etag\"",
                lastModified = "Sat, 01 Aug 2026 03:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.RENATE
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
            result.events shouldHaveSize 14
            result.etag shouldBe "\"renate-etag\""
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

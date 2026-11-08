package de.norm.events.scraper.voidclub

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
 * Unit tests for [VoidClubWebsiteImporter].
 *
 * VOID Club is a single-page source — hand-coded Bootstrap with no API and no per-event pages — so
 * the importer must never request anything beyond the homepage.
 */
class VoidClubWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = VoidClubWebsiteImporter(htmlFetcher)
    private val overviewUrl = "https://www.void-club.de/"

    private fun stubOverview() {
        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = fixtureDocument("voidclub/voidclub-overview.html", overviewUrl),
                etag = "\"void-club-etag\"",
                lastModified = "Tue, 04 Aug 2026 03:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.VOID_CLUB
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
            result.events shouldHaveSize 13
            result.etag shouldBe "\"void-club-etag\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 03:00:00 GMT"
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

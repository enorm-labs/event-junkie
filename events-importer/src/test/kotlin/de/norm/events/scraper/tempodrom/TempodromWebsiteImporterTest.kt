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
 * Tempodrom's listing is JSON-LD, and each event's own page adds the promoter. The server sends
 * `Last-Modified`, so the `NotModified` path is real here rather than theoretical.
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

    private fun stubEventPages() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/tempodrom/tempodrom-detail-trinity-music.html")!!
                .bufferedReader()
                .readText()
        coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(html, firstArg<String>()) }
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
            stubEventPages()
            val result = importer.importEvents(listingUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 143
            result.lastModified shouldBe "Sat, 01 Aug 2026 11:45:00 GMT"
        }

    @Test
    fun `adds the promoter from each event page`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.first().promoters shouldBe listOf("Trinity Music GmbH")
            result.events.first().promoterWebsites shouldBe mapOf("Trinity Music GmbH" to "http://www.trinitymusic.de")
            result.events.first().detailUnavailable shouldBe false
        }

    @Test
    fun `fetches one event page per event and is never skipped on the listing's validators`() =
        runTest {
            stubListing()
            stubEventPages()

            importer.importEvents(listingUrl)

            coVerify(exactly = 143) { htmlFetcher.fetchDocument(any()) }
            importer.fetchesBeyondEntryPage shouldBe true
        }

    @Test
    fun `flags the listing row when the event page fetch fails`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 143
            result.events
                .first()
                .promoters
                .shouldBeEmpty()
            result.events.first().detailUnavailable shouldBe true
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

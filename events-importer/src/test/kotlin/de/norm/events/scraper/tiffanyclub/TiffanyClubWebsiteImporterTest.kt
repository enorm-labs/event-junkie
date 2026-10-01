package de.norm.events.scraper.tiffanyclub

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Unit tests for [TiffanyClubWebsiteImporter]: one listing fetch, then one event page per night. */
class TiffanyClubWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = TiffanyClubWebsiteImporter(htmlFetcher, clock)
    private val listingUrl = "https://tiffany-berlin.de/upcoming-events/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/tiffanyclub/$name")!!
            .bufferedReader()
            .readText()

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("tiffanyclub-overview.html"), listingUrl),
                etag = "\"tiffany-etag\"",
                lastModified = "Thu, 01 Oct 2026 08:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.TIFFANY_CLUB
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports every night with its blurb and propagates conditional headers`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture("tiffanyclub-detail.html"), firstArg<String>()) }

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 32
            result.events.first().description shouldStartWith "🔥 LATIN HELL"
            result.etag shouldBe "\"tiffany-etag\""
            result.lastModified shouldBe "Thu, 01 Oct 2026 08:00:00 GMT"
            coVerify(exactly = 32) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps the listing data when an event page cannot be fetched`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 32
            result.events
                .first()
                .description
                .shouldBeNull()
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", listingUrl), etag = null, lastModified = null)

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }
}

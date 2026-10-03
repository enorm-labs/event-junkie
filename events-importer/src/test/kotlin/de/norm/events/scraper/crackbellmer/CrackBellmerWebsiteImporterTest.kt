package de.norm.events.scraper.crackbellmer

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

/**
 * Unit tests for [CrackBellmerWebsiteImporter].
 *
 * The importer fetches the listing once and then one event page per **upcoming** night, so these
 * cover the fetch count as well as the merge: the venue's own listing carries about a month of past
 * nights, and requesting their pages would be wasted load on the venue's server.
 */
class CrackBellmerWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()

    /** The listing snapshot's capture date, so the past-event cutoff is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = CrackBellmerWebsiteImporter(htmlFetcher, clock)
    private val listingUrl = "https://www.crackbellmer.de/program/this-month"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/crackbellmer/$name")!!
            .bufferedReader()
            .readText()

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("crackbellmer-overview.html"), listingUrl),
                etag = "\"crack-bellmer-etag\"",
                lastModified = "Tue, 04 Aug 2026 03:00:00 GMT"
            )
    }

    private fun stubEventPages(name: String = "crackbellmer-detail-blurb.html") {
        coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture(name), firstArg<String>()) }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.CRACK_BELLMER
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the upcoming nights and propagates conditional headers`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 31
            result.etag shouldBe "\"crack-bellmer-etag\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 03:00:00 GMT"
        }

    @Test
    fun `adds the blurb from each event page`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.first().description shouldStartWith "We're standing at the peak of summer"
            result.events.first().detailUnavailable shouldBe false
        }

    @Test
    fun `fetches one event page per upcoming night and none for the past ones`() =
        runTest {
            stubListing()
            stubEventPages()

            importer.importEvents(listingUrl)

            // 67 items on the page, 35 of them past and one a closed-day marker.
            coVerify(exactly = 31) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `marks the listing row when the event page fetch fails`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 31
            result.events
                .first()
                .description
                .shouldBeNull()
            result.events.first().title shouldBe "BELLMER BALBOA"
            result.events.first().detailUnavailable shouldBe true
        }

    @Test
    fun `marks the listing row when the event page has no blurb`() =
        runTest {
            stubListing()
            stubEventPages("crackbellmer-detail-no-blurb.html")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events
                .first()
                .description
                .shouldBeNull()
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

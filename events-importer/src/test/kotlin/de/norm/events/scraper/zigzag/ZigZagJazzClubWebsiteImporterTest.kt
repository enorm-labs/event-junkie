package de.norm.events.scraper.zigzag

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Unit tests for [ZigZagJazzClubWebsiteImporter]: one listing fetch, then one event page per night. */
class ZigZagJazzClubWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = ZigZagJazzClubWebsiteImporter(htmlFetcher, clock)
    private val listingUrl = "https://www.zigzag-jazzclub.berlin/programmneu"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zigzag/$name")!!
            .bufferedReader()
            .readText()

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("zigzag-programm.html"), listingUrl),
                etag = "\"zigzag-etag\"",
                lastModified = "Thu, 01 Oct 2026 08:00:00 GMT"
            )
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ZIG_ZAG_JAZZ_CLUB
    }

    @Test
    fun `returns NotModified when the listing is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `fills each night from its page and propagates conditional headers`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture("zigzag-detail.html"), firstArg<String>()) }

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 35
            val first = result.events.first()
            first.startTime shouldBe LocalTime.of(20, 0)
            first.doorsTime shouldBe LocalTime.of(19, 0)
            first.pricePresale shouldBe BigDecimal("25")
            first.priceBoxOffice shouldBe BigDecimal("25")
            result.etag shouldBe "\"zigzag-etag\""
            result.lastModified shouldBe "Thu, 01 Oct 2026 08:00:00 GMT"
            coVerify(exactly = 35) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `drops a night whose page names another location`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture("zigzag-detail.html"), firstArg<String>()) }
            coEvery { htmlFetcher.fetchDocument("https://www.zigzag-jazzclub.berlin/program-mai/joelovanocoltra100") } returns
                Jsoup.parse(fixture("zigzag-detail-elsewhere.html"))

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 34
        }

    @Test
    fun `prices a box-office-only night at the door`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture("zigzag-detail-jam.html"), firstArg<String>()) }

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events
                .first()
                .pricePresale
                .shouldBeNull()
            result.events.first().priceBoxOffice shouldBe BigDecimal("20")
        }

    @Test
    fun `keeps the listing data when an event page cannot be fetched`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 35
            result.events
                .first()
                .startTime
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

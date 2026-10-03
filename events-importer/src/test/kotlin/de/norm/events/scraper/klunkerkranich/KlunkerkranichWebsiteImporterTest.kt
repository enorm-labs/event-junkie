package de.norm.events.scraper.klunkerkranich

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
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [KlunkerkranichWebsiteImporter].
 *
 * The importer fetches the programme once and then one event page per night, so these cover the
 * fetch count as well as what the event page contributes on top of the listing — the blurb, the
 * entry charge and the full-size poster — and what is kept when that page cannot be fetched.
 */
class KlunkerkranichWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()

    /** The snapshot's capture date, so the listing scraper's year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = KlunkerkranichWebsiteImporter(htmlFetcher, clock)
    private val listingUrl = "https://klunkerkranich.org/events/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/klunkerkranich/$name")!!
            .bufferedReader()
            .readText()

    private fun stubListing() {
        coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("klunkerkranich-overview.html"), listingUrl),
                etag = "\"klunkerkranich-etag\"",
                lastModified = "Tue, 04 Aug 2026 08:53:43 GMT"
            )
    }

    private fun stubEventPages(name: String = "klunkerkranich-detail-price-range.html") {
        coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse(fixture(name), firstArg<String>()) }
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.KLUNKERKRANICH
    }

    @Test
    fun `returns NotModified when the programme is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(listingUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports the published programme and propagates conditional headers`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 12
            result.events.first().eventDate shouldBe LocalDate.of(2026, 8, 5)
            result.etag shouldBe "\"klunkerkranich-etag\""
            result.lastModified shouldBe "Tue, 04 Aug 2026 08:53:43 GMT"
        }

    @Test
    fun `adds the blurb, the entry charge and the full-size poster from each event page`() =
        runTest {
            stubListing()
            stubEventPages()

            val result = importer.importEvents(listingUrl) as ImportResult.Success
            // Every event page is stubbed with the same fixture, so assert on the night it belongs to.
            val night = result.events.first { it.eventDate == LocalDate.of(2026, 8, 8) }

            night.description shouldStartWith "Ein zweites Mal in 2026"
            night.priceNote shouldBe "5-9€"
            night.priceBoxOffice.shouldBeNull()
            // The listing's thumbnail is a 520x320 crop; the event page links the original.
            night.imageUrl shouldBe "https://klunkerkranich.org/wp-content/uploads/2025/10/Klunkerkranich-kunst-10.Oct25.jpg"
            night.detailUnavailable shouldBe false
        }

    @Test
    fun `stores a lone entry figure as a box-office price`() =
        runTest {
            stubListing()
            stubEventPages("klunkerkranich-detail-single-price.html")

            val result = importer.importEvents(listingUrl) as ImportResult.Success

            result.events.first().priceBoxOffice shouldBe BigDecimal("3")
            result.events
                .first()
                .priceNote
                .shouldBeNull()
        }

    @Test
    fun `fetches one event page per night`() =
        runTest {
            stubListing()
            stubEventPages()

            importer.importEvents(listingUrl)

            coVerify(exactly = 12) { htmlFetcher.fetchDocument(any()) }
        }

    @Test
    fun `keeps the listing data when an event page cannot be fetched`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("boom")

            val result = importer.importEvents(listingUrl) as ImportResult.Success
            val night = result.events.first()

            result.events shouldHaveSize 12
            night.title shouldBe "WOCHENMITTE w. Pascale Project"
            night.description.shouldBeNull()
            night.priceNote.shouldBeNull()
            night.imageUrl shouldBe
                "https://klunkerkranich.org/wp-content/uploads/2026/07/Klunkerkranich-deko-su-hda-18.Jun26-520x320.jpg"
        }

    @Test
    fun `marks the listing row when the event page fetch fails`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("connection reset")

            val result = importer.importEvents(listingUrl) as ImportResult.Success

            result.events.map { it.detailUnavailable }.distinct() shouldBe listOf(true)
        }

    @Test
    fun `marks the listing row when the event page yields no blurb`() =
        runTest {
            stubListing()
            coEvery { htmlFetcher.fetchDocument(any()) } answers { Jsoup.parse("<html><body></body></html>", firstArg<String>()) }

            val result = importer.importEvents(listingUrl) as ImportResult.Success
            val night = result.events.first()

            night.description.shouldBeNull()
            night.detailUnavailable shouldBe true
        }

    @Test
    fun `returns no events for a page without a programme`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><main class=\"c-events-overview\"></main></body></html>", listingUrl),
                    etag = null,
                    lastModified = null
                )

            val result = importer.importEvents(listingUrl) as ImportResult.Success

            result.events.shouldBeEmpty()
        }
}

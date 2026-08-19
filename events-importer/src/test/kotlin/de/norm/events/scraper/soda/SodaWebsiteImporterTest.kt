package de.norm.events.scraper.soda

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [SodaWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge: the detail page supplies the exact date, start time,
 * prices and description, while the overview stands in — with its weekday-inferred date, flyer and
 * ticket link — whenever a detail page fails to fetch.
 */
class SodaWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: SodaWebsiteImporter

    /** Snapshot taken on Thursday 30 July 2026, the listing's first event. */
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 7, 30).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant(), ZoneId.of("Europe/Berlin"))

    private val overviewUrl = "https://www.soda-berlin.de/events"
    private val famousUrl = "https://www.soda-berlin.de/de/events/famous-friday-31-07-2026"
    private val ballermannUrl = "https://www.soda-berlin.de/de/events/ballermann-open-air-150826"
    private val salsaUrl = "https://www.soda-berlin.de/de/events/salsa-sonntag-02-08-2026"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/soda/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = SodaWebsiteImporter(htmlFetcher, clock)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("soda-overview.html"), overviewUrl),
                etag = "\"soda-etag\"",
                lastModified = "Thu, 30 Jul 2026 03:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(famousUrl) } returns
            Jsoup.parse(fixture("soda-detail-famous-friday.html"), famousUrl)
        coEvery { htmlFetcher.fetchDocument(ballermannUrl) } returns
            Jsoup.parse(fixture("soda-detail-open-air.html"), ballermannUrl)
        coEvery { htmlFetcher.fetchDocument(salsaUrl) } returns
            Jsoup.parse(fixture("soda-detail-free.html"), salsaUrl)

        // Every other event's detail page is unavailable, so the importer degrades to
        // overview data (which already carries a real date, so nothing is dropped).
        val stubbed = setOf(famousUrl, ballermannUrl, salsaUrl)
        coEvery { htmlFetcher.fetchDocument(match { it !in stubbed }) } returns
            Jsoup.parse("<html><body></body></html>", overviewUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    private fun event(
        result: ImportResult,
        sourceIdSuffix: String
    ): ScrapedEvent = events(result).first { it.sourceId == "soda:$sourceIdSuffix" }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SODA
    }

    @Test
    fun `returns NotModified when the overview is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 25
            result.etag shouldBe "\"soda-etag\""
            result.lastModified shouldBe "Thu, 30 Jul 2026 03:00:00 GMT"
        }

    @Test
    fun `merges detail time, prices and description onto the overview event`() =
        runTest {
            val famous = event(importer.importEvents(overviewUrl), "famous-friday-31-07-2026")
            // From the detail page:
            famous.startTime shouldBe LocalTime.of(22, 0)
            famous.pricePresale shouldBe BigDecimal("15.43")
            famous.priceBoxOffice shouldBe BigDecimal("15")
            famous.description.shouldNotBeNull()
            // Shared fields resolve consistently across both pages:
            famous.eventDate shouldBe LocalDate.of(2026, 7, 31)
            famous.imageUrl shouldBe "https://soda.disco2app.com/media/events/828/image/23623"
            famous.ticketUrl shouldBe "$famousUrl#tickets"
        }

    @Test
    fun `keeps the free flag of a zero euro resident night through the merge`() =
        runTest {
            val salsa = event(importer.importEvents(overviewUrl), "salsa-sonntag-02-08-2026")
            salsa.free shouldBe true
            salsa.priceBoxOffice shouldBe BigDecimal("0")
            salsa.ticketUrl.shouldBeNull()
        }

    @Test
    fun `handles the venue's alternative slug date spelling end to end`() =
        runTest {
            val ballermann = event(importer.importEvents(overviewUrl), "ballermann-open-air-150826")
            ballermann.eventDate shouldBe LocalDate.of(2026, 8, 15)
            ballermann.pricePresale shouldBe BigDecimal("27.17")
        }

    @Test
    fun `degrades to overview data when a detail page is unavailable`() =
        runTest {
            // Sodalicious' detail page is not stubbed, so the merge falls back to the overview,
            // which still carries a weekday-inferred date, the flyer, and the ticket link.
            val sodalicious = event(importer.importEvents(overviewUrl), "sodalicious-01-08-2026")
            sodalicious.eventDate shouldBe LocalDate.of(2026, 8, 1)
            sodalicious.eventType shouldBe "PARTY"
            sodalicious.imageUrl.shouldNotBeNull()
            sodalicious.startTime.shouldBeNull()
        }

    @Test
    fun `returns an empty list for a page with no event snippets`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body></body></html>", overviewUrl),
                    etag = null,
                    lastModified = null
                )
            events(importer.importEvents(overviewUrl)).shouldHaveSize(0)
        }
}

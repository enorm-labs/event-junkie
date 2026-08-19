package de.norm.events.scraper.morphine

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldHaveSize
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
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MorphineWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge: the detail page supplies the times, lineup, description,
 * image and pricing, while the overview stands in with its date and title whenever a detail page
 * fails to fetch.
 */
class MorphineWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: MorphineWebsiteImporter

    private val overviewUrl = "http://www.morphinerecords.com/events"
    private val sardyUrl = "http://www.morphinerecords.com/events/sardy-fardy-live-recording"
    private val birdsUrl = "http://www.morphinerecords.com/events/all-about-birds-jon-rose-hinterland"
    private val neumannUrl = "http://www.morphinerecords.com/events/neumann-schick-voglsinger"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/morphine/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = MorphineWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("morphine-overview.html"), overviewUrl),
                etag = "\"morphine-etag\"",
                lastModified = "Thu, 06 Aug 2026 13:55:57 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(sardyUrl) } returns
            Jsoup.parse(fixture("morphine-detail-simple.html"), sardyUrl)
        coEvery { htmlFetcher.fetchDocument(birdsUrl) } returns
            Jsoup.parse(fixture("morphine-detail-paypal.html"), birdsUrl)
        coEvery { htmlFetcher.fetchDocument(neumannUrl) } returns
            Jsoup.parse(fixture("morphine-detail-door-price.html"), neumannUrl)

        // Every other night's detail page is unavailable, so the importer degrades to overview
        // data (which already carries a real date, so nothing is dropped).
        val stubbed = setOf(sardyUrl, birdsUrl, neumannUrl)
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
    ): ScrapedEvent = events(result).first { it.sourceId == "morphine:$sourceIdSuffix" }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.MORPHINE
    }

    @Test
    fun `returns NotModified when the overview is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(overviewUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(overviewUrl) shouldBe ImportResult.NotModified
        }

    @Test
    fun `imports every dated night and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(overviewUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            // 11 dated rows; the ARCHIVE navigation row is not an event.
            result.events shouldHaveSize 11
            result.etag shouldBe "\"morphine-etag\""
            result.lastModified shouldBe "Thu, 06 Aug 2026 13:55:57 GMT"
        }

    @Test
    fun `merges detail times, description, image and price onto the overview row`() =
        runTest {
            val sardy = event(importer.importEvents(overviewUrl), "sardy-fardy-live-recording")

            sardy.title shouldBe "Sardy Fardy - Live Recording"
            sardy.eventDate shouldBe LocalDate.of(2026, 8, 7)
            sardy.doorsTime shouldBe LocalTime.of(20, 0)
            sardy.startTime shouldBe LocalTime.of(20, 30)
            sardy.description.shouldNotBeNull()
            sardy.imageUrl.shouldNotBeNull()
            sardy.priceNote shouldBe "Sliding scale 20- 25 Euro at The Door"
        }

    @Test
    fun `prefers the detail lineup over the title-derived headliner`() =
        runTest {
            val birds = event(importer.importEvents(overviewUrl), "all-about-birds-jon-rose-hinterland")

            birds.pricePresale shouldBe BigDecimal("15")
            birds.artists shouldBe
                listOf(
                    ScrapedArtist("ALL ABOUT BIRDS", "HEADLINER"),
                    ScrapedArtist("JON ROSE: HINTERLAND!", "HEADLINER")
                )
        }

    @Test
    fun `degrades to overview data when a detail page is unavailable`() =
        runTest {
            // This night's detail page is not stubbed, so the merge falls back to the overview,
            // which still carries a real date and title.
            val vinyl = event(importer.importEvents(overviewUrl), "vinyl-reduction")
            vinyl.title shouldBe "VINYL REDUCTION - Day 1"
            vinyl.eventDate shouldBe LocalDate.of(2026, 9, 4)
            vinyl.startTime shouldBe null
        }

    @Test
    fun `never imports the ARCHIVE navigation row`() =
        runTest {
            events(importer.importEvents(overviewUrl)).none { it.sourceId == "morphine:ARCHIVE" } shouldBe true
        }
}

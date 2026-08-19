package de.norm.events.scraper.badehaus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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
import java.time.LocalTime

/**
 * Unit tests for [BadehausWebsiteImporter].
 *
 * Two detail pages (a concert with a promoter + start time, and a plain concert) are stubbed
 * explicitly; every other discovered event degrades to its overview data. Focuses on the overview
 * ↔ detail merge.
 */
class BadehausWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: BadehausWebsiteImporter
    private val sourceUrl = "https://badehaus-berlin.com/events/"
    private val donnerUrl = "https://badehaus-berlin.com/events/dominic-donner/"
    private val drunkenUrl = "https://badehaus-berlin.com/events/drunken-swallows/"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/badehaus/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = BadehausWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("badehaus-events.html"), sourceUrl),
                etag = "\"bh-etag\"",
                lastModified = "Mon, 06 Jul 2026 09:00:00 GMT"
            )

        coEvery { htmlFetcher.fetchDocument(donnerUrl) } returns
            Jsoup.parse(fixture("badehaus-detail-promoter.html"), donnerUrl)
        coEvery { htmlFetcher.fetchDocument(drunkenUrl) } returns
            Jsoup.parse(fixture("badehaus-detail-concert.html"), drunkenUrl)

        // Every other detail page returns an empty document, so the importer
        // degrades to the overview data for those events.
        coEvery {
            htmlFetcher.fetchDocument(match { it != donnerUrl && it != drunkenUrl })
        } returns Jsoup.parse("<html><body></body></html>", sourceUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.BADEHAUS
    }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 90
            result.etag shouldBe "\"bh-etag\""
            result.lastModified shouldBe "Mon, 06 Jul 2026 09:00:00 GMT"
        }

    @Test
    fun `enriches an event with detail-page promoter, start time and description`() =
        runTest {
            val donner = events(importer.importEvents(sourceUrl)).first { it.sourceId == "badehaus:dominic-donner" }
            // From the detail page:
            donner.promoters shouldBe listOf("Greyzone")
            donner.startTime shouldBe LocalTime.of(20, 0)
            donner.description.shouldNotBeNull()
            // From the overview page:
            donner.eventType shouldBe EventType.CONCERT.name
            donner.doorsTime shouldBe LocalTime.of(19, 0)
        }

    @Test
    fun `keeps overview description gap when the detail page has no promoter or start time`() =
        runTest {
            val drunken = events(importer.importEvents(sourceUrl)).first { it.sourceId == "badehaus:drunken-swallows" }
            drunken.description.shouldNotBeNull()
            drunken.startTime shouldBe null
            drunken.promoters shouldHaveSize 0
        }

    @Test
    fun `keeps the sold-out flag from the overview when the detail page is empty`() =
        runTest {
            // futurebae's detail page is stubbed empty, so the sold-out flag (a listing
            // CSS class) must survive from the overview through the merge.
            val soldOut = events(importer.importEvents(sourceUrl)).first { it.sourceId == "badehaus:futurebae" }
            soldOut.soldOut shouldBe true
            soldOut.eventType shouldBe EventType.CONCERT.name
        }

    @Test
    fun `keeps the relocated status from the overview through the merge`() =
        runTest {
            val relocated = events(importer.importEvents(sourceUrl)).first { it.sourceId == "badehaus:forager" }
            relocated.status shouldBe EventStatus.RELOCATED.name
        }

    @Test
    fun `returns NotModified when the overview page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }
}

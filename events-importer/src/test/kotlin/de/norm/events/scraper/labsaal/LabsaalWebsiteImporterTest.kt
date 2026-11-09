package de.norm.events.scraper.labsaal

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [LabsaalWebsiteImporter], with the saved feed and inline event pages behind mocked fetchers. */
class LabsaalWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = LabsaalWebsiteImporter(apiClient, htmlFetcher)
    private val listingUrl = "https://labsaal.de/events/"
    private val requestUrl = slot<String>()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureText("labsaal/labsaal-feed.ics")
        coEvery { htmlFetcher.fetchDocument(any()) } answers { costPage("16,- / 14,- / 10,-", firstArg()) }
        coEvery { htmlFetcher.fetchDocument(MARKET) } answers { costPage("Eintritt frei", MARKET) }
        coEvery { htmlFetcher.fetchDocument(KARWENDEL) } throws IllegalStateException("page down")
    }

    private fun costPage(
        cost: String,
        url: String
    ) = Jsoup.parse(
        """<div class="mec-wrap"><div class="mec-event-cost"><div class="mec-cost">Preise</div>
          <dl><dd class="mec-events-event-cost">$cost</dd></dl></div></div>""",
        url
    )

    @Test
    fun `importEvents takes each night's Preise line from its page and keeps the feed's row for a failed page`() =
        runTest {
            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val market = result.events.single { it.sourceUrl == MARKET }
            market.free shouldBe true
            market.priceNote.shouldBeNull()
            val tiered = result.events.first { it.sourceUrl != MARKET && it.sourceUrl != KARWENDEL }
            tiered.priceNote shouldBe "16,- / 14,- / 10,-"
            tiered.pricePresale.shouldBeNull()
            tiered.priceBoxOffice.shouldBeNull()
            tiered.free shouldBe false
            val failed = result.events.single { it.sourceUrl == KARWENDEL }
            val feedRow = LabsaalCalendarScraper().scrape(fixtureText("labsaal/labsaal-feed.ics")).single { it.sourceUrl == KARWENDEL }
            failed shouldBe feedRow.copy(detailUnavailable = true)
        }

    @Test
    fun `importEvents reads the calendar feed on the listing's host`() =
        runTest {
            val result = importer.importEvents(listingUrl)

            requestUrl.captured shouldBe "https://labsaal.de/?mec-ical-feed=1"
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 22
            result.events.all { it.sourceId.startsWith("labsaal:") } shouldBe true
        }

    @Test
    fun `importEvents returns no validators, because the feed is read in full`() =
        runTest {
            val result = importer.importEvents(listingUrl, etag = "\"abc\"", lastModified = "Wed, 01 Jul 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for a calendar without events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `importer reads the whole programme under its own source`() {
        importer.eventSource shouldBe EventSource.LABSAAL
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private companion object {
        const val MARKET = "https://labsaal.de/events/luebarser-weihnachtsmarkt-2025/"
        const val KARWENDEL = "https://labsaal.de/events/wohnzimmerkonzert-zum-advent-karwendel/"
    }
}

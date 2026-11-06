package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [UrbanSpreeWebsiteImporter].
 *
 * Mocks [HtmlFetcher] so the whole paginated fetch (listing pages → per-event detail
 * pages) runs offline against the saved fixtures. The clock is fixed to 2026-07-30, the
 * day the fixtures were captured, so the "has this page reached the past?" cutoff lands on
 * the third listing page exactly as it did live.
 *
 * The third page is served the `urbanspree-overview-past-boundary.html` snapshot — the
 * live page whose nine cards straddle that cutoff — so the walk stops there.
 */
class UrbanSpreeWebsiteImporterTest {
    private lateinit var importer: UrbanSpreeWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://www.urbanspree.com/program/"
    private val page2Url = "https://www.urbanspree.com/program/?page=2"
    private val page3Url = "https://www.urbanspree.com/program/?page=3"
    private val page4Url = "https://www.urbanspree.com/program/?page=4"
    private val exhibitionsUrl = "https://www.urbanspree.com/program/exhibitions/"

    private val clock = Clock.fixed(LocalDate.of(2026, 7, 30).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/urbanspree/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private fun emptyListing(baseUrl: String): Document = Jsoup.parse("""<html><body><div id="pdopage"></div></body></html>""", baseUrl)

    @BeforeEach
    fun setUp() {
        importer = UrbanSpreeWebsiteImporter(htmlFetcher, clock)
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("urbanspree-overview.html", entryUrl)
        coEvery { htmlFetcher.fetchDocument(page2Url) } returns fixture("urbanspree-overview-page2.html", page2Url)
        coEvery { htmlFetcher.fetchDocument(page3Url) } returns fixture("urbanspree-overview-past-boundary.html", page3Url)
        coEvery { htmlFetcher.fetchDocument(page4Url) } returns emptyListing(page4Url)
        coEvery { htmlFetcher.fetchDocument(exhibitionsUrl) } returns emptyListing(exhibitionsUrl)
        // Every detail page that is not stubbed explicitly falls back to the listing card's data.
        coEvery { htmlFetcher.fetchDocument(match { it.contains("/program/concerts/") }) } throws IllegalStateException("no fixture")
        coEvery { htmlFetcher.fetchDocument(TWIN_NOIR_URL) } returns fixture("urbanspree-detail-concert.html", TWIN_NOIR_URL)
        coEvery { htmlFetcher.fetchDocument(SOM_URL) } returns fixture("urbanspree-detail-cancelled.html", SOM_URL)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.URBAN_SPREE
    }

    @Test
    fun `importEvents walks pages until the listing reaches the past`() =
        runTest {
            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // 9 + 9 upcoming, then 8 of the boundary page's 9 (its 2026-07-29 card is already past).
            result.events shouldHaveSize 26
            result.events.none { it.eventDate.isBefore(LocalDate.of(2026, 7, 30)) } shouldBe true
            // Multi-page importer intentionally disables conditional caching.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents keeps the first page when a later listing page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws RuntimeException("connection reset")

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 9
            result.complete shouldBe false
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(page3Url) }
        }

    @Test
    fun `importEvents stops paginating at the boundary page instead of walking the archive`() =
        runTest {
            importer.importEvents(entryUrl)

            coVerify(exactly = 1) { htmlFetcher.fetchDocument(page3Url) }
            // The venue's listing runs to 200+ archive pages; page 4 must never be requested.
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(page4Url) }
        }

    @Test
    fun `importEvents skips the detail fetch for a card that is already in the past`() =
        runTest {
            importer.importEvents(entryUrl)

            coVerify(exactly = 0) { htmlFetcher.fetchDocument(PAST_EVENT_URL) }
        }

    @Test
    fun `importEvents merges the detail page over the listing card`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.single { it.sourceUrl == TWIN_NOIR_URL }

            // Detail page wins on the fields only it carries.
            event.promoters shouldHaveSize 1
            event.ticketUrl.shouldNotBeNull()
            event.description.shouldNotBeNull()
            event.artists.map { it.name } shouldBe listOf("TWIN NOIR", "HINFORT")
            // The listing card keeps the date/time (machine-readable) and the full-size poster.
            event.eventDate shouldBe LocalDate.of(2026, 12, 12)
            event.startTime shouldBe LocalTime.of(20, 0)
            event.imageUrl shouldBe "https://www.urbanspree.com/assets/project/urbanspree/mediasource/IMG_1389.JPG"
            event.detailUnavailable shouldBe false
        }

    @Test
    fun `importEvents keeps a cancellation announced on both pages`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.single { it.sourceUrl == SOM_URL }.status shouldBe EventStatus.CANCELLED.name
        }

    @Test
    fun `importEvents degrades to the listing card when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.single { it.sourceId == "urban_spree:concerts/coilguns-berlin-urban-spree" }

            // Card-only data survives the failed detail fetch.
            event.title shouldBe "Coilguns"
            event.eventDate shouldBe LocalDate.of(2026, 12, 5)
            event.description.shouldBeNull()
            event.promoters shouldHaveSize 0
        }

    @Test
    fun `marks the listing card when the detail page fetch fails`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.single { it.sourceId == "urban_spree:concerts/coilguns-berlin-urban-spree" }.detailUnavailable shouldBe true
        }

    @Test
    fun `marks the listing card when the detail page parses to nothing`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(TWIN_NOIR_URL) } returns Jsoup.parse("<html><body></body></html>", TWIN_NOIR_URL)

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.single { it.sourceUrl == TWIN_NOIR_URL }

            event.detailUnavailable shouldBe true
            event.detailPageOwns shouldBe setOf(ScrapedField.TITLE, ScrapedField.SUBTITLE, ScrapedField.EVENT_TYPE)
            event.description.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success when the listing carries no events`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns emptyListing(entryUrl)

            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents appends the page parameter to an entry URL that already has a query`() =
        runTest {
            val queryEntryUrl = "https://www.urbanspree.com/program/?lang=en"
            coEvery { htmlFetcher.fetchDocument(queryEntryUrl) } returns fixture("urbanspree-overview.html", queryEntryUrl)
            coEvery { htmlFetcher.fetchDocument("$queryEntryUrl&page=2") } returns emptyListing(queryEntryUrl)
            coEvery { htmlFetcher.fetchDocument("https://www.urbanspree.com/program/exhibitions/?lang=en") } returns emptyListing(queryEntryUrl)

            importer.importEvents(queryEntryUrl)

            coVerify(exactly = 1) { htmlFetcher.fetchDocument("$queryEntryUrl&page=2") }
        }

    private fun lateCard(href: String) =
        Jsoup.parse(
            """<html><head><base href="https://www.urbanspree.com/"></head><body><div id="pdopage">
            <a class="card" href="$href" data-dateStart="2026-09-25 23:59:00">
            <div class="card-text cat">Concerts</div><div class="card-text title">Urban Spree KLUBNACHT 005</div></a>
            </div></body></html>""",
            entryUrl
        )

    @Test
    fun `importEvents replaces the card's 23-59 placeholder with the start the detail page resolves`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns lateCard("program/concerts/urban-spree-klubnacht-005.html")
            coEvery { htmlFetcher.fetchDocument(page2Url) } returns emptyListing(page2Url)
            coEvery { htmlFetcher.fetchDocument(KLUBNACHT_URL) } returns fixture("urbanspree-detail-klubnacht.html", KLUBNACHT_URL)

            val event =
                importer
                    .importEvents(entryUrl)
                    .shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .single()

            event.startTime shouldBe LocalTime.of(21, 0)
            event.eventType shouldBe EventType.PARTY.name
        }

    @Test
    fun `importEvents keeps the card's 23-59 when the detail page states no other start`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns lateCard("program/concerts/urban-spree-klubnacht-005.html")
            coEvery { htmlFetcher.fetchDocument(page2Url) } returns emptyListing(page2Url)
            val detail = fixture("urbanspree-detail-klubnacht.html", KLUBNACHT_URL)
            detail.select(".rte.tv-content p").remove()
            coEvery { htmlFetcher.fetchDocument(KLUBNACHT_URL) } returns detail

            val event =
                importer
                    .importEvents(entryUrl)
                    .shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .single()

            event.startTime shouldBe LocalTime.of(23, 59)
        }

    @Test
    fun `importEvents stores the card's time as doors when the detail page reads a later start`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
                Jsoup.parse(
                    """<html><head><base href="https://www.urbanspree.com/"></head><body><div id="pdopage">
                    <a class="card" href="program/concerts/otha-berlin-urban-spree.html" data-dateStart="2026-10-07 19:00:00">
                    <div class="card-text cat">Concerts</div><div class="card-text title">Otha - Berlin - Urban Spree</div></a>
                    </div></body></html>""",
                    entryUrl
                )
            coEvery { htmlFetcher.fetchDocument(page2Url) } returns emptyListing(page2Url)
            coEvery { htmlFetcher.fetchDocument(OTHA_URL) } returns fixture("urbanspree-detail-doors-clock-first.html", OTHA_URL)

            val event =
                importer
                    .importEvents(entryUrl)
                    .shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .single()

            event.doorsTime shouldBe LocalTime.of(19, 0)
            event.startTime shouldBe LocalTime.of(20, 0)
        }

    @Test
    fun `importEvents keeps an untimed exhibition run untimed through the detail merge`() =
        runTest {
            val event = importWholetrain(detailFixture = "urbanspree-detail-exhibition.html", cardStart = "2026-11-06")

            event.title shouldBe "WON ABC, NEON: Wholetrain 20"
            // The page's category slot names the artists, so the card's Exhibitions label types the run.
            event.eventType shouldBe EventType.EXHIBITION.name
            event.eventDate shouldBe LocalDate.of(2026, 11, 6)
            event.startTime.shouldBeNull()
            event.endDate shouldBe LocalDate.of(2026, 11, 28)
            event.free shouldBe true
            event.detailUnavailable shouldBe false
        }

    // #4517: an untimed card, and a captured page whose hero holds the closing date and whose category slot names the artists.
    @Test
    fun `importEvents takes an exhibition's start from the Vernissage row of its page`() =
        runTest {
            val event = importWholetrain(detailFixture = "urbanspree-detail-exhibition-vernissage.html", cardStart = "2026-11-06")

            event.eventType shouldBe EventType.EXHIBITION.name
            event.eventDate shouldBe LocalDate.of(2026, 11, 6)
            event.startTime shouldBe LocalTime.of(18, 0)
            event.endDate shouldBe LocalDate.of(2026, 11, 28)
            event.endTime.shouldBeNull()
        }

    @Test
    fun `importEvents keeps a timed exhibition card's time over the Vernissage row`() =
        runTest {
            val event = importWholetrain(detailFixture = "urbanspree-detail-exhibition-vernissage.html", cardStart = "2026-11-06 19:30:00")

            event.startTime shouldBe LocalTime.of(19, 30)
        }

    private suspend fun importWholetrain(
        detailFixture: String,
        cardStart: String
    ): ScrapedEvent {
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
            Jsoup.parse(
                """<html><head><base href="https://www.urbanspree.com/"></head><body><div id="pdopage">
                <a class="stealth swiper-slide card" href="program/exhibitions/wholetrain-20.html"
                   data-datestart="$cardStart" data-dateend="2026-11-28" data-getdate="2026-10-23">
                <p class="card-text mb-0 cat">Exhibitions</p><p class="card-text mb-0 title">Wholetrain 20</p></a>
                </div></body></html>""",
                entryUrl
            )
        coEvery { htmlFetcher.fetchDocument(page2Url) } returns emptyListing(page2Url)
        coEvery { htmlFetcher.fetchDocument(WHOLETRAIN_URL) } returns fixture(detailFixture, WHOLETRAIN_URL)

        return importer
            .importEvents(entryUrl)
            .shouldBeInstanceOf<ImportResult.Success>()
            .events
            .single()
    }

    private fun importerOn(date: LocalDate) = UrbanSpreeWebsiteImporter(htmlFetcher, Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))

    /** The entry URL serves the live page that held Wholetrain on 2026-11-06, between concerts of both sides of the opening. */
    private fun serveRunningExhibitionListing() {
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("urbanspree-overview-running-exhibition.html", entryUrl)
        coEvery { htmlFetcher.fetchDocument(WHOLETRAIN_URL) } returns fixture("urbanspree-detail-exhibition-vernissage.html", WHOLETRAIN_URL)
    }

    /** No card on the listing, and the live category page: nine shows, of which only Wholetrain closes after 2026-11-06. */
    private fun serveExhibitionsPageOnly() {
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns emptyListing(entryUrl)
        coEvery { htmlFetcher.fetchDocument(exhibitionsUrl) } returns fixture("urbanspree-exhibitions.html", exhibitionsUrl)
        coEvery { htmlFetcher.fetchDocument(WHOLETRAIN_URL) } returns fixture("urbanspree-detail-exhibition-vernissage.html", WHOLETRAIN_URL)
    }

    @Test
    fun `importEvents keeps a running exhibition the day after it opens`() =
        runTest {
            serveRunningExhibitionListing()

            val result = importerOn(LocalDate.of(2026, 11, 7)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.single { it.sourceUrl == WHOLETRAIN_URL }

            event.eventDate shouldBe LocalDate.of(2026, 11, 6)
            event.endDate shouldBe LocalDate.of(2026, 11, 28)
            event.startTime shouldBe LocalTime.of(18, 0)
            event.eventType shouldBe EventType.EXHIBITION.name
            result.complete shouldBe true
        }

    @Test
    fun `importEvents still drops a concert dated yesterday, and spares its detail fetch`() =
        runTest {
            serveRunningExhibitionListing()
            val yesterday = "https://www.urbanspree.com/program/concerts/tigercub-nets-to-catch-the-wind-berlin.html"

            val result = importerOn(LocalDate.of(2026, 11, 7)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            // The page's four concerts from 2026-11-07 on, and the run; its four older concerts stay out.
            result.events.none { it.sourceUrl == yesterday } shouldBe true
            result.events shouldHaveSize 5
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(yesterday) }
        }

    @Test
    fun `importEvents ends the walk at a running exhibition that opened before today`() =
        runTest {
            // The run is the only card that started before today, so it alone can end the walk.
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns
                Jsoup.parse(
                    """<html><head><base href="https://www.urbanspree.com/"></head><body><div id="pdopage">
                    <a class="stealth swiper-slide card" href="program/concerts/no-cigar.html" data-dateStart="2026-11-07 19:00:00">
                    <p class="card-text mb-0 cat">Concerts</p><p class="card-text mb-0 title">No Cigar</p></a>
                    <a class="stealth swiper-slide card" href="program/exhibitions/wholetrain-20.html"
                       data-datestart="2026-11-06" data-dateend="2026-11-28" data-getdate="2026-11-06">
                    <p class="card-text mb-0 cat">Exhibitions</p><p class="card-text mb-0 title">Wholetrain 20</p></a>
                    </div></body></html>""",
                    entryUrl
                )
            coEvery { htmlFetcher.fetchDocument(WHOLETRAIN_URL) } returns fixture("urbanspree-detail-exhibition-vernissage.html", WHOLETRAIN_URL)

            val result = importerOn(LocalDate.of(2026, 11, 7)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 2
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(page2Url) }
        }

    @Test
    fun `importEvents reads a run the walk never reaches from the exhibitions page`() =
        runTest {
            serveExhibitionsPageOnly()

            val result = importerOn(LocalDate.of(2026, 11, 20)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
            val event = result.events.single()

            event.sourceId shouldBe "urban_spree:exhibitions/wholetrain-20"
            event.eventDate shouldBe LocalDate.of(2026, 11, 6)
            event.endDate shouldBe LocalDate.of(2026, 11, 28)
            event.eventType shouldBe EventType.EXHIBITION.name
            event.detailUnavailable shouldBe false
        }

    @Test
    fun `importEvents keeps a run on its closing day and drops it the day after`() =
        runTest {
            serveExhibitionsPageOnly()

            val closingDay = importerOn(LocalDate.of(2026, 11, 28)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
            val dayAfter = importerOn(LocalDate.of(2026, 11, 29)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            closingDay.events shouldHaveSize 1
            dayAfter.events shouldHaveSize 0
        }

    @Test
    fun `importEvents skips a closed show on the exhibitions page and its detail fetch`() =
        runTest {
            serveExhibitionsPageOnly()

            val result = importerOn(LocalDate.of(2026, 11, 7)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceUrl } shouldBe listOf(WHOLETRAIN_URL)
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(RECONNECTIONS_URL) }
        }

    @Test
    fun `importEvents yields one event for a card on both the listing and the exhibitions page`() =
        runTest {
            serveRunningExhibitionListing()
            coEvery { htmlFetcher.fetchDocument(exhibitionsUrl) } returns fixture("urbanspree-exhibitions.html", exhibitionsUrl)

            val result = importerOn(LocalDate.of(2026, 11, 7)).importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.count { it.sourceUrl == WHOLETRAIN_URL } shouldBe 1
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(WHOLETRAIN_URL) }
        }

    @Test
    fun `importEvents keeps the listing's events and a complete run when the exhibitions page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(exhibitionsUrl) } throws RuntimeException("connection reset")

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 26
            result.complete shouldBe true
        }

    @Test
    fun `importEvents does not mark a broken walk complete because the exhibitions page answered`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(page2Url) } throws RuntimeException("connection reset")
            coEvery { htmlFetcher.fetchDocument(exhibitionsUrl) } returns fixture("urbanspree-exhibitions.html", exhibitionsUrl)

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.complete shouldBe false
        }

    @Test
    fun `importEvents carries a cancelled run from the exhibitions page`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns emptyListing(entryUrl)
            coEvery { htmlFetcher.fetchDocument(exhibitionsUrl) } returns
                Jsoup.parse(
                    """<html><head><base href="https://www.urbanspree.com/"></head><body><div id="pdopage">
                    <a class="stealth swiper-slide card" href="program/exhibitions/wholetrain-20.html"
                       data-datestart="2026-11-06" data-dateend="2026-11-28" data-getdate="2026-11-06">
                    <p class="card-text mb-0 cat">Exhibitions</p><p class="card-text mb-0 title">CANCELLED - Wholetrain 20</p></a>
                    </div></body></html>""",
                    exhibitionsUrl
                )
            coEvery { htmlFetcher.fetchDocument(WHOLETRAIN_URL) } throws IllegalStateException("no fixture")

            val event =
                importerOn(LocalDate.of(2026, 11, 7))
                    .importEvents(entryUrl)
                    .shouldBeInstanceOf<ImportResult.Success>()
                    .events
                    .single()

            event.status shouldBe EventStatus.CANCELLED.name
            event.endDate shouldBe LocalDate.of(2026, 11, 28)
        }

    @Test
    fun `importEvents asks for the exhibitions page once, keeping an entry URL's query`() =
        runTest {
            val queryEntryUrl = "https://www.urbanspree.com/program/?lang=en"
            val queryExhibitionsUrl = "https://www.urbanspree.com/program/exhibitions/?lang=en"
            coEvery { htmlFetcher.fetchDocument(queryEntryUrl) } returns emptyListing(queryEntryUrl)
            coEvery { htmlFetcher.fetchDocument(queryExhibitionsUrl) } returns emptyListing(queryExhibitionsUrl)

            importer.importEvents(queryEntryUrl)

            coVerify(exactly = 1) { htmlFetcher.fetchDocument(queryExhibitionsUrl) }
        }

    @Test
    fun `importEvents finds the exhibitions page under an entry URL without a trailing slash`() =
        runTest {
            val bareEntryUrl = "https://www.urbanspree.com/program"
            coEvery { htmlFetcher.fetchDocument(bareEntryUrl) } returns emptyListing(bareEntryUrl)

            importer.importEvents(bareEntryUrl)

            coVerify(exactly = 1) { htmlFetcher.fetchDocument(exhibitionsUrl) }
        }

    private companion object {
        private const val RECONNECTIONS_URL = "https://www.urbanspree.com/program/exhibitions/reconnections.html"
        private const val WHOLETRAIN_URL = "https://www.urbanspree.com/program/exhibitions/wholetrain-20.html"
        private const val OTHA_URL = "https://www.urbanspree.com/program/concerts/otha-berlin-urban-spree.html"
        private const val KLUBNACHT_URL = "https://www.urbanspree.com/program/concerts/urban-spree-klubnacht-005.html"
        private const val TWIN_NOIR_URL = "https://www.urbanspree.com/program/concerts/twin-noir-hinfort-urban-spree,-berlin.html"
        private const val SOM_URL = "https://www.urbanspree.com/program/concerts/som-berlin-urban-spree.html"

        /** The boundary page's 2026-07-29 card — already past on the fixed test clock. */
        private const val PAST_EVENT_URL = "https://www.urbanspree.com/program/concerts/vmo-violent-magic-orchestra-urban-spree.html"
    }
}

package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
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

    private companion object {
        private const val KLUBNACHT_URL = "https://www.urbanspree.com/program/concerts/urban-spree-klubnacht-005.html"
        private const val TWIN_NOIR_URL = "https://www.urbanspree.com/program/concerts/twin-noir-hinfort-urban-spree,-berlin.html"
        private const val SOM_URL = "https://www.urbanspree.com/program/concerts/som-berlin-urban-spree.html"

        /** The boundary page's 2026-07-29 card — already past on the fixed test clock. */
        private const val PAST_EVENT_URL = "https://www.urbanspree.com/program/concerts/vmo-violent-magic-orchestra-urban-spree.html"
    }
}

package de.norm.events.scraper.gaertenderwelt

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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [GaertenDerWeltWebsiteImporter].
 *
 * Mocks [HtmlFetcher] so the whole walk (listing pages → per-event detail pages) runs offline
 * against the saved fixtures. Three listing pages are stubbed — the live first page, its second,
 * and the live *last* page, which renders no "nächste" link and therefore ends the walk. Only the
 * concert detail page has a fixture; every other detail fetch throws, exercising the degrade-to-
 * listing-data fallback on the same run.
 */
class GaertenDerWeltWebsiteImporterTest {
    private lateinit var importer: GaertenDerWeltWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/gaertenderwelt/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private fun emptyListing(baseUrl: String): Document =
        Jsoup.parse("""<html><body><div class="tx-events2"><div class="list"></div></div></body></html>""", baseUrl)

    @BeforeEach
    fun setUp() {
        importer = GaertenDerWeltWebsiteImporter(htmlFetcher)
        // Anything not stubbed below — every detail page but the concert — degrades to listing data.
        coEvery { htmlFetcher.fetchDocument(any()) } throws IllegalStateException("no fixture")
        coEvery { htmlFetcher.fetchDocument(ENTRY_URL) } returns fixture("gaertenderwelt-overview.html", ENTRY_URL)
        coEvery { htmlFetcher.fetchDocument(PAGE2_URL) } returns fixture("gaertenderwelt-overview-page2.html", PAGE2_URL)
        coEvery { htmlFetcher.fetchDocument(PAGE3_URL) } returns fixture("gaertenderwelt-overview-last.html", PAGE3_URL)
        coEvery { htmlFetcher.fetchDocument(CONCERT_URL) } returns fixture("gaertenderwelt-detail-concert.html", CONCERT_URL)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.GAERTEN_DER_WELT
    }

    @Test
    fun `importEvents walks the paginator until a page offers no next link`() =
        runTest {
            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()

            // 1 in-scope row on page 1, 2 on page 2, 1 on the last page.
            result.events shouldHaveSize 4
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(PAGE3_URL) }
            // TYPO3 clamps an out-of-range page to the last one, so a fourth request would loop forever.
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(PAGE4_URL) }
            // A multi-page listing intentionally disables conditional caching.
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents never fetches a detail page for a filtered-out row`() =
        runTest {
            importer.importEvents(ENTRY_URL)

            coVerify(exactly = 0) { htmlFetcher.fetchDocument(GUIDED_TOUR_URL) }
        }

    @Test
    fun `importEvents merges the detail page over the listing row`() =
        runTest {
            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()
            val concert = result.events.single { it.sourceUrl == CONCERT_URL }

            // Detail page wins on the fields only it carries.
            concert.doorsTime shouldBe LocalTime.of(17, 30)
            concert.pricePresale shouldBe BigDecimal("60.00")
            concert.priceBoxOffice shouldBe BigDecimal("65")
            concert.promoters shouldBe listOf("Loft Concert GmbH")
            concert.description.shouldNotBeNull()
            // The listing row keeps the date, start time and the type its category names.
            concert.eventDate shouldBe LocalDate.of(2026, 8, 15)
            concert.startTime shouldBe LocalTime.of(19, 0)
            concert.eventType shouldBe EventType.CONCERT.name
        }

    @Test
    fun `importEvents bills the headliner and the support act of a concert`() =
        runTest {
            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()
            val concert = result.events.single { it.sourceUrl == CONCERT_URL }

            concert.artists.map { it.name } shouldBe listOf("Agnes Obel", "Peter Gregson")
            concert.artists.map { it.role } shouldBe listOf("HEADLINER", "SUPPORT")
        }

    @Test
    fun `importEvents mints no artist for a non-concert event`() =
        runTest {
            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()

            result.events.single { it.title == "Spieleabend" }.artists shouldHaveSize 0
        }

    @Test
    fun `importEvents degrades to the listing row when a detail page cannot be fetched`() =
        runTest {
            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()
            val screening = result.events.single { it.title.startsWith("Wanderkino") }

            screening.eventDate shouldBe LocalDate.of(2026, 8, 20)
            screening.startTime shouldBe LocalTime.of(21, 0)
            screening.eventType shouldBe EventType.SCREENING.name
            screening.description.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success when the listing carries no rows`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(ENTRY_URL) } returns emptyListing(ENTRY_URL)

            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents folds the days of one exhibition slug into a run and leaves a multi-night booking apart`() =
        runTest {
            // The park lists an exhibition once per open day, each under its own stamp (ADR-029, #337).
            fun row(
                stamp: String,
                slug: String,
                category: String,
                title: String
            ) = """
                <div class="eventWrapper"><div class="date">x</div><div class="eventInner">
                  <div class="category">$category</div>
                  <h3 class="media-heading"><a href="/events/veranstaltungen/detail/$stamp/$slug/" title="$title">$title</a></h3>
                  <div class="time">9 Uhr</div><p class="textMedium">t</p>
                </div></div>
                """
            val listing =
                Jsoup.parse(
                    """<html><body><div class="tx-events2"><div class="list">
                    ${row("2026-09-01_0900", "zwischen-himmel-und-erde", "Ausstellungen", "Zwischen Himmel und Erde: Ausstellung")}
                    ${row("2026-09-02_0900", "zwischen-himmel-und-erde", "Ausstellungen", "Zwischen Himmel und Erde: Ausstellung")}
                    ${row("2026-09-13_0900", "zwischen-himmel-und-erde", "Ausstellungen", "Zwischen Himmel und Erde: Ausstellung")}
                    ${row("2026-09-05_2100", "drohnenshow", "Konzerte", "Drohnenshow")}
                    ${row("2026-09-06_2100", "drohnenshow", "Konzerte", "Drohnenshow")}
                    </div></div></body></html>""",
                    ENTRY_URL
                )
            coEvery { htmlFetcher.fetchDocument(ENTRY_URL) } returns listing

            val result = importer.importEvents(ENTRY_URL).shouldBeInstanceOf<ImportResult.Success>()

            val run = result.events.single { it.eventType == "EXHIBITION" }
            run.sourceId shouldBe "gaerten_der_welt:zwischen-himmel-und-erde"
            run.eventDate shouldBe LocalDate.of(2026, 9, 1)
            run.endDate shouldBe LocalDate.of(2026, 9, 13)
            run.startTime shouldBe LocalTime.of(9, 0)
            // A drone show over two nights shares a slug and keeps both nights: only exhibitions fold.
            result.events.count { it.title == "Drohnenshow" } shouldBe 2
        }

    private companion object {
        private const val ENTRY_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/"
        private const val PAGE2_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/page2/"
        private const val PAGE3_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/page3/"
        private const val PAGE4_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/page4/"

        private const val CONCERT_URL = "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-08-15_1900/agnes-obel/"

        /** A first-page row the breadth filter drops — its detail page must never be requested. */
        private const val GUIDED_TOUR_URL =
            "https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-08-08_1100/" +
                "fuehrung-durch-die-gaerten-der-welt-die-highlight-tour-serie/"
    }
}

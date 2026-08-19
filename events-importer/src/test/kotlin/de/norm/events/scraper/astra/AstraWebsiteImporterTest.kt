package de.norm.events.scraper.astra

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
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

/**
 * Unit tests for [AstraWebsiteImporter].
 *
 * Focuses on the overview ↔ detail merge behaviour.
 */
class AstraWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: AstraWebsiteImporter
    private val sourceUrl = "https://www.astra-berlin.de/"
    private val teaserUrl = "https://www.astra-berlin.de/events/2026-06-13-berlin-breakout--2026"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/astra/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = AstraWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("astra-overview.html"), sourceUrl),
                etag = "\"astra-etag\"",
                lastModified = "Sat, 13 Jun 2026 10:00:00 GMT"
            )

        val greenLungUrl = "https://www.astra-berlin.de/events/2026-05-18-green-lung"
        val chapoUrl = "https://www.astra-berlin.de/events/2026-12-09-chapo102"
        coEvery { htmlFetcher.fetchDocument(greenLungUrl) } returns
            Jsoup.parse(fixture("astra-detail-green-lung.html"), greenLungUrl)
        coEvery { htmlFetcher.fetchDocument(chapoUrl) } returns
            Jsoup.parse(fixture("astra-detail-chapo.html"), chapoUrl)

        // Day 3 of the festival: Astra mislabels it as "Konzert" on the detail
        // page too, so the merge must not let it override the overview's
        // normalized FESTIVAL type.
        val day3Url = "https://www.astra-berlin.de/events/2027-05-08-out-of-line-weekender-2027"
        coEvery { htmlFetcher.fetchDocument(day3Url) } returns
            Jsoup.parse(
                """
                <html><body><main class="page-content"><article><header class="event"><div class="event__middle-col">
                <div class="event__kind"><div class="event__label">Konzert</div></div>
                <h1 class="event__title"><a class="event__title-link" href="/events/2027-05-08-out-of-line-weekender-2027">OUT OF LINE WEEKENDER 2027</a></h1>
                <div class="event__subtitle">Day 3</div></div></header></article></main></body></html>
                """.trimIndent(),
                day3Url
            )

        // The featured teaser has no date on the overview (UNRESOLVED_EVENT_DATE sentinel);
        // its detail page supplies the real date, which the merge must adopt.
        coEvery { htmlFetcher.fetchDocument(teaserUrl) } returns
            Jsoup.parse(
                """
                <html><body><main class="page-content"><article><header class="event">
                <div class="event__left-col"><div class="event__date event__date--full">13.06.26</div></div>
                <div class="event__middle-col"><div class="event__kind"><div class="event__label">Festival</div></div>
                <h1 class="event__title"><a class="event__title-link" href="/events/2026-06-13-berlin-breakout--2026">BERLIN BREAKOUT! 2026</a></h1>
                </div></header></article></main></body></html>
                """.trimIndent(),
                teaserUrl
            )

        // Other detail pages (plain concert) — return empty so the importer
        // degrades to overview data.
        coEvery {
            htmlFetcher.fetchDocument(
                match { it != greenLungUrl && it != chapoUrl && it != day3Url && it != teaserUrl }
            )
        } returns Jsoup.parse(EMPTY_DETAIL, sourceUrl)
    }

    private fun events(result: ImportResult): List<de.norm.events.scraper.ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ASTRA
    }

    @Test
    fun `imports all events and propagates conditional headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 7
            result.etag shouldBe "\"astra-etag\""
            result.lastModified shouldBe "Sat, 13 Jun 2026 10:00:00 GMT"
        }

    @Test
    fun `fills the dateless teaser date from its detail page`() =
        runTest {
            // The teaser has no date on the overview (sentinel); the detail page supplies it.
            val teaser = events(importer.importEvents(sourceUrl)).first { it.title == "BERLIN BREAKOUT! 2026" }
            teaser.eventDate shouldBe LocalDate.of(2026, 6, 13)
        }

    @Test
    fun `drops a dateless event whose detail page cannot resolve a date`() =
        runTest {
            // Simulate the teaser's detail page being unavailable: the date stays at the
            // sentinel after the merge, so the event must be dropped rather than persisted
            // with a garbage date.
            coEvery { htmlFetcher.fetchDocument(teaserUrl) } returns Jsoup.parse(EMPTY_DETAIL, sourceUrl)

            val events = events(importer.importEvents(sourceUrl))
            events shouldHaveSize 6
            events.none { it.title == "BERLIN BREAKOUT! 2026" } shouldBe true
            events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        }

    @Test
    fun `merges detail promoter and price with overview artists`() =
        runTest {
            val greenLung = events(importer.importEvents(sourceUrl)).first { it.title == "GREEN LUNG" }
            // From the detail page:
            greenLung.promoters shouldBe listOf("Landstreicher Konzerte")
            greenLung.pricePresale shouldBe BigDecimal("39.90")
            greenLung.description.shouldNotBeNull()
            // From the overview page (the detail page has no roster):
            greenLung.artists shouldHaveSize 3
            greenLung.eventDate shouldBe LocalDate.of(2026, 12, 11)
        }

    @Test
    fun `falls back to overview type and date when the detail page is empty`() =
        runTest {
            val angus = events(importer.importEvents(sourceUrl)).first { it.title.contains("ANGUS") }
            angus.eventType shouldBe "CONCERT"
            angus.eventDate shouldBe LocalDate.of(2026, 7, 8)
            angus.artists shouldHaveSize 1
        }

    @Test
    fun `keeps the normalized festival type even when the detail page is mislabeled`() =
        runTest {
            // Day 3's detail page carries kind "Konzert", but the overview corrected
            // it to FESTIVAL via its festival siblings — the overview type must win.
            val day3 =
                events(importer.importEvents(sourceUrl))
                    .first { it.sourceUrl.endsWith("2027-05-08-out-of-line-weekender-2027") }
            day3.eventType shouldBe "FESTIVAL"
            day3.artists shouldHaveSize 0
        }

    @Test
    fun `keeps the sold-out flag from both pages`() =
        runTest {
            val chapo = events(importer.importEvents(sourceUrl)).first { it.title == "CHAPO102" }
            chapo.soldOut shouldBe true
        }

    private companion object {
        private const val EMPTY_DETAIL = "<html><body><main class=\"page-content\"></main></body></html>"
    }
}

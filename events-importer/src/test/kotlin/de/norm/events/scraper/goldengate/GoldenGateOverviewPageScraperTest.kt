package de.norm.events.scraper.goldengate

import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.withWeekdayWarnings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [GoldenGateOverviewPageScraper].
 *
 * Uses a real homepage snapshot of the announced Thursday–Saturday block, plus hand-built documents
 * for the shapes the live page does not currently show (a night with no lineup, a date line without
 * a time). The fixture is what proves the parser keys off heading *content* rather than Elementor's
 * per-element hashes: the page's trailing non-event headings ("Tickets only available at the
 * door.", "SHOPPING") sit in the same heading stream and must not be read as a night.
 */
class GoldenGateOverviewPageScraperTest {
    private val scraper = GoldenGateOverviewPageScraper()
    private val baseUrl = "https://goldengate-berlin.de/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(fixture("goldengate-overview.html"), baseUrl)
    }

    private fun fixture(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/goldengate/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private fun headings(vararg texts: String) =
        Jsoup.parse(
            texts.joinToString("") { """<h2 class="elementor-heading-title">$it</h2>""" },
            baseUrl
        )

    @Test
    fun `stores every night Techno, House, the club's sound, since the venue names no style`() {
        AcceptedLimitations.withHouseGenre(EventSource.GOLDEN_GATE, events).map { it.genre }.distinct() shouldBe listOf("Techno, House")
    }

    @Test
    fun `extracts the announced Thursday to Saturday block`() {
        events shouldHaveSize 3
        events.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 7, 30),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1)
            )
    }

    @Test
    fun `parses every field of a night`() {
        val donnerdogge = events.first()
        donnerdogge.title shouldBe "Donnerdogge"
        donnerdogge.eventType shouldBe "PARTY"
        donnerdogge.eventDate shouldBe LocalDate.of(2026, 7, 30)
        donnerdogge.startTime shouldBe LocalTime.of(23, 59)
        donnerdogge.sourceUrl shouldBe baseUrl
        donnerdogge.sourceId shouldBe "golden_gate:2026-07-30-donnerdogge"
        donnerdogge.status shouldBe "SCHEDULED"
        // The venue publishes none of these anywhere on the page.
        donnerdogge.description.shouldBeNull()
        donnerdogge.imageUrl.shouldBeNull()
        donnerdogge.ticketUrl.shouldBeNull()
        donnerdogge.pricePresale.shouldBeNull()
        donnerdogge.artists shouldContainExactly
            listOf(
                ScrapedArtist("Neco", "DJ"),
                ScrapedArtist("Jeremy Reinhard", "DJ"),
                ScrapedArtist("Yannick Robyns", "DJ")
            )
    }

    @Test
    fun `splits a back-to-back billing into two DJs`() {
        events[1].artists shouldContainExactly
            listOf(
                ScrapedArtist("Sean Dixon", "DJ"),
                ScrapedArtist("Koljah", "DJ"),
                ScrapedArtist("Nyna Curtis", "DJ"),
                ScrapedArtist("Kisling", "DJ"),
                ScrapedArtist("Berunth", "DJ")
            )
    }

    @Test
    fun `splits a b2b slot into two DJs`() {
        val parsed = scraper.scrape(headings("Do. 01. Oktober 2026 - 23:59", "Cura", "Thor Rixon<br>Anaté b2b Shira Kela"), baseUrl)
        parsed.single().artists.map { it.name } shouldContainExactly listOf("Thor Rixon", "Anaté", "Shira Kela")
    }

    @Test
    fun `distinguishes two nights that share a title by their date`() {
        val klubnaechte = events.filter { it.title == "Klubnacht" }
        klubnaechte shouldHaveSize 2
        klubnaechte.map { it.sourceId } shouldContainExactly
            listOf("golden_gate:2026-07-31-klubnacht", "golden_gate:2026-08-01-klubnacht")
    }

    @Test
    fun `ignores the page's trailing non-event headings`() {
        events.map { it.title } shouldContainExactly listOf("Donnerdogge", "Klubnacht", "Klubnacht")
    }

    @Test
    fun `does not read the next night's date line as a lineup`() {
        val parsed = scraper.scrape(headings("Do. 30. Juli 2026 - 23:59", "Donnerdogge", "Fr. 31. Juli 2026 - 23:59", "Klubnacht"), baseUrl)
        parsed shouldHaveSize 2
        parsed[0].title shouldBe "Donnerdogge"
        parsed[0].artists.shouldBeEmpty()
        parsed[1].title shouldBe "Klubnacht"
    }

    @Test
    fun `accepts a date line without a door time`() {
        val parsed = scraper.scrape(headings("Sa. 01. August 2026", "Klubnacht", "SaPu"), baseUrl)
        parsed shouldHaveSize 1
        parsed[0].eventDate shouldBe LocalDate.of(2026, 8, 1)
        parsed[0].startTime.shouldBeNull()
    }

    @Test
    fun `skips a date line with no title heading after it`() {
        scraper.scrape(headings("Sa. 01. August 2026 - 23:59"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without headings`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `moves a night whose weekday names the neighbouring month, as on the live page of 2 October 2026`() {
        // The page printed "Sa. 03. September 2026": 3 September is a Thursday, 3 October the Saturday of the block.
        val (parsed, warnings) = withWeekdayWarnings { scraper.scrape(fixture("goldengate-overview-month-typo.html"), baseUrl) }
        parsed.map { it.sourceId } shouldContainExactly
            listOf(
                "golden_gate:2026-10-01-cura",
                "golden_gate:2026-10-02-wohnzimmer030-afterhour",
                "golden_gate:2026-10-03-nightshade-family"
            )
        parsed.last().eventDate shouldBe LocalDate.of(2026, 10, 3)
        val warning = warnings.single().formattedMessage
        warning shouldContain "Sa. 03. September 2026 - 23:59"
        warning shouldContain "2026-09-03"
        warning shouldContain "2026-10-03"
    }

    @Test
    fun `logs nothing for headings whose weekday agrees with the date`() {
        withWeekdayWarnings { scraper.scrape(fixture("goldengate-overview.html"), baseUrl) }.second.shouldBeEmpty()
    }

    @Test
    fun `keeps the printed date of a night whose weekday fits no neighbouring month`() {
        // 3 October 2026 is a Saturday; neither 3 September (Thursday) nor 3 November (Tuesday) is a Friday.
        val (parsed, warnings) =
            withWeekdayWarnings {
                scraper.scrape(headings("Do. 01. Oktober 2026 - 23:59", "Cura", "Fr. 03. Oktober 2026 - 23:59", "Klubnacht"), baseUrl)
            }
        parsed.map { it.title to it.eventDate } shouldContainExactly
            listOf("Cura" to LocalDate.of(2026, 10, 1), "Klubnacht" to LocalDate.of(2026, 10, 3))
        warnings.single().formattedMessage shouldContain "Fr. 03. Oktober 2026 - 23:59"
    }

    @Test
    fun `keeps the printed date when the corrected date sits outside the block`() {
        // 3 October fits the weekday, but the block's confirmed night is in late July.
        val parsed = scraper.scrape(headings("Do. 30. Juli 2026 - 23:59", "Donnerdogge", "Sa. 03. September 2026 - 23:59", "Klubnacht"), baseUrl)
        parsed.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 9, 3))
    }

    @Test
    fun `keeps the printed date of a lone night with a mismatched weekday, since nothing confirms the correction`() {
        scraper.scrape(headings("Sa. 03. September 2026 - 23:59", "Klubnacht"), baseUrl).single().eventDate shouldBe LocalDate.of(2026, 9, 3)
    }
}

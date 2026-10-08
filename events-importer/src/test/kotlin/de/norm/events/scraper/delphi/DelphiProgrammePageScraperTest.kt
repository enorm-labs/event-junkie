package de.norm.events.scraper.delphi

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [DelphiProgrammePageScraper].
 *
 * Parses a static snapshot of the Theater im Delphi `/programm/` page for deterministic,
 * offline-safe testing without HTTP fetching. The fixture keeps the page's leaked record dump
 * alongside the rendered table, so the price join is exercised on real data.
 */
class DelphiProgrammePageScraperTest {
    private val scraper = DelphiProgrammePageScraper()
    private val baseUrl = "https://theater-im-delphi.de/programm/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/delphi/delphi-programm.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private val credits: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/delphi/delphi-programm-credits.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers one event per performance, not per production`() {
        // 24 rows across five month headings, resolving to 14 productions.
        events shouldHaveSize 24
        events.map { it.sourceUrl }.distinct() shouldHaveSize 14
    }

    @Test
    fun `maps a fully populated row`() {
        val stokes = event("theater_im_delphi:525/2026-11-14-19:30")
        stokes.title shouldBe "Genevieve Stokes"
        stokes.eventType shouldBe EventType.CONCERT.name
        stokes.eventDate shouldBe LocalDate.of(2026, 11, 14)
        stokes.startTime shouldBe LocalTime.of(19, 30)
        stokes.sourceUrl shouldBe "https://theater-im-delphi.de/programm/?prod=525"
        stokes.imageUrl!! shouldStartWith "https://theater-im-delphi.de/wp-content/uploads/programm/"
        stokes.description!!.isNotBlank() shouldBe true
        stokes.artists.map { it.name } shouldBe listOf("Genevieve Stokes")
    }

    @Test
    fun `takes the month and year from the heading above the table`() {
        // The row states only the day; "August 2026" / "Dezember 2026" supply the rest.
        event("theater_im_delphi:528/2026-08-09-18:00").eventDate shouldBe LocalDate.of(2026, 8, 9)
        event("theater_im_delphi:519/2026-12-10-19:30").eventDate shouldBe LocalDate.of(2026, 12, 10)
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `keeps a matinee and an evening of the same production apart`() {
        // Four productions play twice in one day; only the clock distinguishes them.
        val twentySeventh = events.filter { it.eventDate == LocalDate.of(2026, 9, 27) }
        twentySeventh shouldHaveSize 2
        twentySeventh.map { it.startTime } shouldBe listOf(LocalTime.of(15, 0), LocalTime.of(20, 0))
        twentySeventh.map { it.sourceId } shouldBe
            listOf("theater_im_delphi:488/2026-09-27-15:00", "theater_im_delphi:488/2026-09-27-20:00")
        events.map { it.sourceId }.toSet() shouldHaveSize 24
    }

    @Test
    fun `types the venue's staging formats`() {
        // The model has no dance or theatre type, so both are staged shows.
        event("theater_im_delphi:488/2026-09-27-15:00").eventType shouldBe EventType.SHOW.name
        event("theater_im_delphi:531/2026-10-13-11:00").eventType shouldBe EventType.SHOW.name
        event("theater_im_delphi:538/2026-09-13-12:00").eventType shouldBe EventType.READING.name
        event("theater_im_delphi:512/2026-10-08-19:00").eventType shouldBe EventType.CONCERT.name
    }

    @Test
    fun `types an unlabelled row as a concert from its teaser`() {
        // Both rows carry an empty label cell; the teasers name "Songwriterinnen" and "Folksongs" (#2899).
        val lisa = event("theater_im_delphi:524/2026-10-09-20:00")
        lisa.eventType shouldBe EventType.CONCERT.name
        lisa.artists.map { it.name } shouldBe listOf("LISA O'NEILL")
        val fussell = event("theater_im_delphi:519/2026-12-10-19:30")
        fussell.eventType shouldBe EventType.CONCERT.name
        fussell.artists.map { it.name } shouldBe listOf("Jake Xerxes Fussell")
        events.none { it.eventType == null } shouldBe true
    }

    @Test
    fun `leaves an unlabelled row with no concert cue as OTHER`() {
        val wrestling = credits.single { it.title == "Odeum Pro Wrestling Catche Diem 2026" }
        wrestling.eventType shouldBe EventType.OTHER.name
        wrestling.artists.shouldBeEmpty()
    }

    @Test
    fun `bills a concert's credit line, not its programme title`() {
        // The title names the programme; `p.stabText` names who plays it (#2899).
        event("theater_im_delphi:512/2026-10-08-19:00").artists.map { it.name } shouldBe
            listOf("Ensemble Utak", "Hannah Dienes-Williams", "R. Marino Arcaro")
    }

    @Test
    fun `leaves the composer and the film's director off a credit line`() {
        // The teaser credits "Musik von Johannes Brahms" and "(1922, F.W. Murnau)": authors, not performers.
        val nosferatu = credits.single { it.title == "Nosferatu. Eine Symphonie des Grauens" }
        nosferatu.eventType shouldBe EventType.CONCERT.name
        nosferatu.artists.map { it.name } shouldBe listOf("Golden Star String Quartet", "Katarzyna Marek")
        nosferatu.artists.map { it.role }.distinct() shouldBe listOf("HEADLINER")
    }

    @Test
    fun `ignores a staged row's credit line, which names the company`() {
        event("theater_im_delphi:531/2026-10-13-11:00").artists.map { it.name } shouldNotContain "FLUIDUM"
    }

    @Test
    fun `stores a genre only where the label names one`() {
        // "Kammermusik" and "Elektronische Musik" are genres; "Tanz" and "Theater" are formats.
        event("theater_im_delphi:512/2026-10-08-19:00").genre shouldBe "Kammermusik"
        event("theater_im_delphi:528/2026-08-09-18:00").genre shouldBe "Elektronische Musik"
        event("theater_im_delphi:488/2026-09-27-15:00").genre.shouldBeNull()
        events.count { it.genre != null } shouldBe 2
    }

    @Test
    fun `recovers prices from the page's leaked record dump`() {
        // The rendered page states no price anywhere; the var_dump comment is the only source.
        val prometheus = event("theater_im_delphi:531/2026-10-13-11:00")
        prometheus.pricePresale shouldBe BigDecimal("5")
        prometheus.priceNote shouldBe "5–18 €"
        // A flat price renders without a range.
        val lisa = event("theater_im_delphi:524/2026-10-09-20:00")
        lisa.pricePresale shouldBe BigDecimal("34.75")
        lisa.priceNote shouldBe "34,75 €"
    }

    @Test
    fun `prefers the production amounts, which carry the cents the per-event copies round away`() {
        // The dump states event 29/32 and production 29.95/32.45 for the same performance.
        val swanLake = event("theater_im_delphi:488/2026-09-27-15:00")
        swanLake.pricePresale shouldBe BigDecimal("29.95")
        swanLake.priceNote shouldBe "29,95–32,45 €"
    }

    @Test
    fun `reads the free-entry flag and stores no price for those`() {
        val free = events.filter { it.free }
        free.map { it.title } shouldBe
            listOf("Delphis Orakel x Makoto Sakamoto", "Tag des offenen Denkmals", "SheLeadsNature: Strukturen ändern, nicht die Frauen!")
        free.forEach {
            it.pricePresale.shouldBeNull()
            it.priceNote.shouldBeNull()
        }
    }

    @Test
    fun `reads each row's own ticket-shop link`() {
        // The venue sells through whichever shop the production uses, per row.
        event("theater_im_delphi:525/2026-11-14-19:30").ticketUrl!! shouldStartWith "https://www.eventim.de/"
        event("theater_im_delphi:531/2026-10-13-11:00").ticketUrl!! shouldStartWith "https://19871.reservix.de/"
        event("theater_im_delphi:519/2026-12-10-19:30").ticketUrl!! shouldStartWith "https://www.tixforgigs.com/"
        // A free date renders a `.kein-ticket-link` placeholder with no href.
        event("theater_im_delphi:528/2026-08-09-18:00").ticketUrl.shouldBeNull()
        events.count { it.ticketUrl == null } shouldBe 2
    }

    @Test
    fun `joins the dump on production and start time, so every paid date gets its price`() {
        events.count { it.pricePresale != null } shouldBe 21
        events.count { it.pricePresale == null } shouldBe 3
    }

    @Test
    fun `survives a page whose leaked dump has been fixed away`() {
        // The leak is an accident; losing it must cost only the prices.
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/delphi/delphi-programm.html")!!
                .bufferedReader()
                .readText()
        val withoutDump = html.replace(Regex("""<!--array\(23\).*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val stripped = scraper.scrape(Jsoup.parse(withoutDump, baseUrl), baseUrl)

        stripped shouldHaveSize 24
        stripped.none { it.pricePresale != null } shouldBe true
        stripped.none { it.free } shouldBe true
        stripped.first().title shouldBe "Delphis Orakel x Makoto Sakamoto"
    }

    @Test
    fun `returns an empty list for a page without a programme`() {
        val document = Jsoup.parse("<html><body><section class='events_section'></section></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

package de.norm.events.scraper.friedrichstadtpalast

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [FriedrichstadtPalastGastspielePageScraper], against a saved copy of the real page. */
class FriedrichstadtPalastGastspielePageScraperTest {
    private val scraper = FriedrichstadtPalastGastspielePageScraper()
    private val baseUrl = "https://www.palast.berlin/gastspiele/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/friedrichstadtpalast/friedrichstadtpalast-gastspiele.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun show(title: String) = events.first { it.title == title }

    private fun scrapeSection(inner: String) =
        scraper.scrape(
            Jsoup.parse("""<section class="content-with-photo"><div class="text">$inner</div></section>""", baseUrl),
            baseUrl
        )

    @Test
    fun `scrape reads one event per ticket button`() {
        events shouldHaveSize 8
        events.map { it.eventDate.toString() } shouldContainExactly
            listOf("2026-11-30", "2026-12-28", "2026-12-28", "2027-01-11", "2027-03-01", "2027-03-15", "2027-04-05", "2027-05-10")
    }

    @Test
    fun `scrape maps a concert with its promoter, ticket shop and image`() {
        val event = show("FRANK SCHÖBEL")

        event.subtitle shouldBe "65jähriges Bühnenjubiläum – Jubiläumstournee"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2027, 5, 10)
        event.startTime shouldBe LocalTime.of(18, 0)
        event.doorsTime.shouldBeNull()
        event.sourceUrl shouldBe "https://www.palast.berlin/gastspiele/#99157"
        event.sourceId shouldBe "friedrichstadtpalast:99157-2027-05-10"
        event.ticketUrl shouldStartWith "https://www.eventim.de/event/frank-schoebel-band"
        event.imageUrl shouldBe "https://www.palast.berlin/wp-content/uploads/2026/09/FrankSchoebel_by_MarkusNass_72dpi.jpg"
        event.promoters shouldContainExactly listOf("MB-Konzerte GmbH")
        event.artists.map { it.name } shouldContainExactly listOf("FRANK SCHÖBEL")
        event.description!! shouldStartWith "In den Annalen der Geschichte"
    }

    @Test
    fun `the description stops at the promoter's contact block`() {
        val description = show("Drei Haselnüsse für Aschenbrödel").description!!

        description shouldContain "Orchester: Deutsches Filmorchester Babelsberg"
        description shouldNotContain "Veranstalter"
        description shouldNotContain "Mannheim"
    }

    @Test
    fun `two performances on one date get the time in their id`() {
        events.filter { it.title == "Drei Haselnüsse für Aschenbrödel" }.map { it.sourceId } shouldContainExactly
            listOf("friedrichstadtpalast:99150-2026-12-28-1600", "friedrichstadtpalast:99150-2026-12-28-1930")
    }

    @Test
    fun `the heading names the format`() {
        show("Drei Haselnüsse für Aschenbrödel").eventType shouldBe "SCREENING"
        show("Drei Haselnüsse für Aschenbrödel").subtitle shouldBe "Der Originalfilm mit Live-Orchester"
        show("Lars Eidinger singt und liest Brechts „Hauspostille“").eventType shouldBe "READING"
    }

    @Test
    fun `a tour year is cut from the heading`() {
        show("Suzi Quatro & Band").subtitle.shouldBeNull()
        show("Ulrich Tukur & Die Rhythmus Boys").eventType shouldBe "CONCERT"
    }

    @Test
    fun `a genre heading bills no act and a conductor credit is not part of the name`() {
        show("CHANSON").artists.shouldBeEmpty()
        show("GLENN MILLER ORCHESTRA FOR EUROPE DIRECTED BY ULI PLETTENDORFF").artists.map { it.name } shouldContainExactly
            listOf("GLENN MILLER ORCHESTRA FOR EUROPE")
    }

    @Test
    fun `a button without a date and time is skipped and the show keeps its readable dates`() {
        val events =
            scrapeSection(
                """
                <p><a name="1"></a><strong>Some Act</strong></p><p>Am Freitag im Frühjahr.</p>
                <a class="button" href="https://tickets.example/a"><span>Tickets 12.02.2027 Uhr</span></a>
                <a class="button" href="https://tickets.example/b"><span>Tickets 13.02.2027, 20:00 Uhr</span></a>
                """.trimIndent()
            )

        events.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2027, 2, 13))
        events.single().ticketUrl shouldBe "https://tickets.example/b"
    }

    @Test
    fun `a show without a ticket button is skipped, whatever its prose says`() {
        scrapeSection("""<p><a name="2"></a><strong>Some Act</strong></p><p>Am 12.02.2027 um 20 Uhr im Palast.</p>""").shouldBeEmpty()
    }

    @Test
    fun `an impossible date on a button is skipped`() {
        scrapeSection("""<p><a name="3"></a><strong>Some Act</strong></p><a class="button" href="x"><span>Tickets 31.02.2027, 20:00 Uhr</span></a>""")
            .shouldBeEmpty()
    }

    @Test
    fun `a show without an anchor is skipped`() {
        scrapeSection("""<p><strong>Some Act</strong></p><a class="button" href="x"><span>Tickets 13.02.2027, 20:00 Uhr</span></a>""")
            .shouldBeEmpty()
    }

    @Test
    fun `a sold-out button marks the performance sold out`() {
        scrapeSection(
            """<p><a name="4"></a><strong>Some Act</strong></p><a class="button" href="x"><span>Tickets 13.02.2027, 20:00 Uhr – ausverkauft</span></a>"""
        ).single().soldOut shouldBe true
    }

    @Test
    fun `a page without guest shows yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Keine Gastspiele</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}

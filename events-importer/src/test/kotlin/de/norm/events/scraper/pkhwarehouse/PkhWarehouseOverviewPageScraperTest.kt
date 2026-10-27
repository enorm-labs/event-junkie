package de.norm.events.scraper.pkhwarehouse

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [PkhWarehouseOverviewPageScraper], against a saved copy of the real home page. */
class PkhWarehouseOverviewPageScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), BERLIN)
    private val scraper = PkhWarehouseOverviewPageScraper(clock)
    private val baseUrl = "https://www.pkh.events/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/pkhwarehouse/pkhwarehouse-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun djs(date: String) = night(date).artists.map { it.name }

    private fun parse(programme: String) = scraper.scrape(Jsoup.parse("""<section id="s2"><div class="rahmen">$programme</div></section>""", baseUrl), baseUrl)

    @Test
    fun `scrape reads every night under its month heading, past ones included`() {
        events.map { it.eventDate } shouldContainExactly
            listOf(
                "2026-10-09",
                "2026-10-10",
                "2026-10-12",
                "2026-10-13",
                "2026-10-14",
                "2026-10-15",
                "2026-10-16",
                "2026-10-17",
                "2026-10-24",
                "2026-10-31",
                "2026-11-07",
                "2026-11-13",
                "2026-11-21",
                "2026-12-05"
            ).map(LocalDate::parse)
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = night("2026-10-10")

        event.title shouldBe "PARALLAX"
        event.subtitle shouldBe "xXETEXx · FeelNature · 7Chakras"
        event.description shouldBe "xXETEXx · FeelNature · 7Chakras — Adam Vandal, Josephine Wedekind, TMH Tranzit, EMIRI TSUKUI, HypoGeo"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.endTime shouldBe LocalTime.of(8, 0)
        event.endDate shouldBe LocalDate.parse("2026-10-11")
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "pkh_warehouse:2026-10-10"
        event.ticketUrl.shouldBeNull()
        event.artists.map { it.role }.distinct() shouldContainExactly listOf(ArtistRole.DJ)
        djs("2026-10-10") shouldContainExactly listOf("Adam Vandal", "Josephine Wedekind", "TMH Tranzit", "EMIRI TSUKUI", "HypoGeo")
    }

    @Test
    fun `floor labels are dropped, the night's own ticket link is kept and an open end has no end time`() {
        val event = night("2026-10-17")

        event.title shouldBe "IRRENHOUSE · The Art of Light"
        event.subtitle shouldBe "Nina Queer · offizielle Party des Festival of Lights"
        event.description shouldBe
            "Nina Queer · offizielle Party des Festival of Lights — Eleete, Stefan Biniak, Alex Blume · Pop Floor: U-Seven, Pomoz · " +
            "Shows: Nina Queer, Sascha Glam, Katy Bam"
        event.ticketUrl shouldBe "https://aboutix.com/organizers/nina-queer/events/irrenhouse171026"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.endTime.shouldBeNull()
        event.endDate.shouldBeNull()
        djs("2026-10-17") shouldContainExactly
            listOf("Eleete", "Stefan Biniak", "Alex Blume", "U-Seven", "Pomoz", "Nina Queer", "Sascha Glam", "Katy Bam")
    }

    @Test
    fun `a colon inside a name is not a floor label`() {
        djs("2026-10-31") shouldContainExactly
            listOf(
                "Andreas Henneberg",
                "Joplyn",
                "Gourski",
                "Nina Hepburn",
                "TraumaMia",
                "Maurice Mino",
                "Sarah Wild",
                "Sin:port",
                "Susi&Paula",
                "Bizzarro Universe",
                "WIEK",
                "Leevey",
                "agls",
                "KIMMO"
            )
        night("2026-10-31").endDate shouldBe LocalDate.parse("2026-11-01")
    }

    @Test
    fun `a line-up without a dash is a list of names when it has commas, and a set note is dropped`() {
        night("2026-10-12").subtitle.shouldBeNull()
        djs("2026-10-12") shouldContainExactly listOf("Asem Shama")
        djs("2026-10-13") shouldContainExactly listOf("Charlotte Lion", "CosmiKat")
    }

    @Test
    fun `a single phrase without a dash or a comma names no artist`() {
        val event = night("2026-10-09")

        event.description shouldBe "1. Festival of Lights Clubnight"
        event.subtitle.shouldBeNull()
        event.artists.shouldBeEmpty()
        event.endTime shouldBe LocalTime.of(4, 0)
    }

    @Test
    fun `a night without a line-up has only its title`() {
        val event = night("2026-10-24")

        event.title shouldBe "ILLEGAL"
        event.description.shouldBeNull()
        event.artists.shouldBeEmpty()
        event.startTime shouldBe LocalTime.of(23, 0)
        event.endTime shouldBe LocalTime.of(6, 0)
    }

    @Test
    fun `the weekday moves a night in a month that has passed into next year`() {
        val events =
            parse(
                """<h3>Januar</h3><div class="zeile"><div class="d">09</div><div class="t">Neujahr</div><div class="z">Sa 22:00 — 08:00</div></div>"""
            )

        events.map { it.eventDate } shouldContainExactly listOf(LocalDate.parse("2027-01-09"))
    }

    @Test
    fun `a night under an unreadable heading or with an unreadable day is skipped`() {
        val events =
            parse(
                """
                <h3>Demnächst</h3><div class="zeile"><div class="d">01</div><div class="t">Lost</div><div class="z">Sa 22:00</div></div>
                <h3>November</h3><div class="zeile"><div class="d">TBA</div><div class="t">Undated</div><div class="z">Sa 22:00</div></div>
                <div class="zeile"><div class="d">31</div><div class="t">No such day</div><div class="z">Sa 22:00</div></div>
                <div class="zeile"><div class="d">28</div><div class="t"></div><div class="z">Sa 22:00</div></div>
                <div class="zeile"><div class="d">14</div><div class="t">Kept</div><div class="z">Sa 22:00 — 06:00</div></div>
                """
            )

        events.map { it.title } shouldContainExactly listOf("Kept")
        events.single().eventDate shouldBe LocalDate.parse("2026-11-14")
    }

    @Test
    fun `scrape returns nothing for a page without a programme`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}

package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WuehlmaeuseProductionPageScraperTest {
    private val scraper = WuehlmaeuseProductionPageScraper()

    private val pageUrl = "https://wuehlmaeuse.de/veranstaltung/rudelsingen/"

    @Test
    fun `reads the show text after the empty heading`() {
        val description =
            scraper
                .scrape(fixtureDocument("wuehlmaeuse/wuehlmaeuse-production-rudelsingen.html", pageUrl))
                .shouldNotBeNull()
                .description
                .shouldNotBeNull()

        description shouldStartWith
            "David Rauterberg und Martin Hunger präsentieren die schönsten Lieder zum Mitsingen – Schlager, Evergreens, Pop und Rock. " +
            "Das Einstimmen ist dabei ausdrücklich erwünscht: "
        description.lines().last() shouldBe "Hinweis: „Im Stehen singt es sich am besten!“ (David Rauterberg)"
        description.lines().size shouldBe 3
    }

    @Test
    fun `drops a line that is only a link, and the note box after the text`() {
        val description = scraper.scrape(fixtureDocument("wuehlmaeuse/wuehlmaeuse-production-rudelsingen.html", pageUrl))?.description.shouldNotBeNull()

        description shouldNotContain "Homepage"
        description shouldNotContain "BITTE BEACHTEN"
    }

    @Test
    fun `reads the placeholder of a production not yet described as no text`() {
        scraper
            .scrape(fixtureDocument("wuehlmaeuse/wuehlmaeuse-production-placeholder.html", pageUrl))
            .shouldNotBeNull()
            .description
            .shouldBeNull()
    }

    @Test
    fun `keeps inline markup inside a line`() {
        val html =
            """<div class="elementor-widget-heading"><div class="elementor-widget-container"><p class="elementor-heading-title">""" +
                """<h5 class="elementor-heading-title"></h5>Ein <b>sehr</b> langer Abend mit <a href="/x">Gästen</a>.<br />Zweite Zeile""" +
                """<div class="elementor">Box</div></div></div>"""

        scraper.scrape(Jsoup.parse(html))?.description shouldBe "Ein sehr langer Abend mit Gästen.\nZweite Zeile"
    }

    @Test
    fun `takes the dated guest lines out of the show text`() {
        // Event 13539: the 2026 guests stood on every 2027 Nightwash show.
        val production = scraper.scrape(Jsoup.parse(nightwashPage())).shouldNotBeNull()

        production.description shouldBe "NightWash ist die Kult-Marke für Stand-Up Comedy in Deutschland."
        production.guests shouldBe
            listOf(
                GuestLine(
                    9,
                    11,
                    listOf("Ben Schafmeister", "Ilkan Ates", "Mirja Regensburg"),
                    "Gäste am 09.11.: Ben Schafmeister, Ilkan Ates, Mirja Regensburg"
                ),
                GuestLine(10, 11, listOf("Abdel Boudii", "Pam Pengco"), "Gäste am 10.11.: Abdel Boudii und Pam Pengco")
            )
    }

    @Test
    fun `a guest line belongs only to a performance on its day within the horizon`() {
        val guests = scraper.scrape(Jsoup.parse(nightwashPage())).shouldNotBeNull().guests
        val today = LocalDate.of(2026, 11, 1)

        guestLineFor(LocalDate.of(2026, 11, 9), guests, today)?.names shouldBe listOf("Ben Schafmeister", "Ilkan Ates", "Mirja Regensburg")
        guestLineFor(LocalDate.of(2027, 11, 9), guests, today).shouldBeNull()
        guestLineFor(LocalDate.of(2026, 11, 9), guests, LocalDate.of(2026, 11, 12)).shouldBeNull()
        guestLineFor(LocalDate.of(2026, 11, 11), guests, today).shouldBeNull()
    }

    @Test
    fun `parses a page without the text widget to null`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Seite nicht gefunden</p></body></html>")).shouldBeNull()
    }

    private fun nightwashPage(): String =
        """<div class="elementor-widget-heading"><div class="elementor-widget-container"><p class="elementor-heading-title">""" +
            """<h5 class="elementor-heading-title"></h5>Gäste am 09.11.: Ben Schafmeister, Ilkan Ates, Mirja Regensburg<br />""" +
            """Gäste am 10.11.: Abdel Boudii und Pam Pengco<br />""" +
            """NightWash ist die Kult-Marke für Stand-Up Comedy in Deutschland.<div class="elementor">Box</div></div></div>"""
}

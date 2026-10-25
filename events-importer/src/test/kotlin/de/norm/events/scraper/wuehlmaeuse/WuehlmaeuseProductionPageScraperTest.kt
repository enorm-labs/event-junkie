package de.norm.events.scraper.wuehlmaeuse

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class WuehlmaeuseProductionPageScraperTest {
    private val scraper = WuehlmaeuseProductionPageScraper()

    private fun loadDocument(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/wuehlmaeuse/$name")!!
                .bufferedReader()
                .readText(),
            "https://wuehlmaeuse.de/veranstaltung/rudelsingen/"
        )

    @Test
    fun `reads the show text after the empty heading`() {
        val description =
            scraper
                .scrape(loadDocument("wuehlmaeuse-production-rudelsingen.html"))
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
        val description = scraper.scrape(loadDocument("wuehlmaeuse-production-rudelsingen.html"))?.description.shouldNotBeNull()

        description shouldNotContain "Homepage"
        description shouldNotContain "BITTE BEACHTEN"
    }

    @Test
    fun `reads the placeholder of a production not yet described as no text`() {
        scraper
            .scrape(loadDocument("wuehlmaeuse-production-placeholder.html"))
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
    fun `parses a page without the text widget to null`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Seite nicht gefunden</p></body></html>")).shouldBeNull()
    }
}

package de.norm.events.scraper.galilaeakirche

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

class GalilaeakircheEventPageScraperTest {
    private val scraper = GalilaeakircheEventPageScraper()

    private fun scrape(name: String) = scraper.scrape(document(name), url(name))!!

    @Test
    fun `reads the doors time, the box-office price and the band text`() {
        val event = scrape("zea")

        event.title shouldBe "ZEA / ZE4"
        event.sourceId shouldBe "galilaeakirche:zea"
        event.doorsTime shouldBe LocalTime.of(19, 30)
        event.priceBoxOffice shouldBe BigDecimal("20")
        event.pricePresale.shouldBeNull()
        event.priceNote.shouldBeNull()
        event.ticketUrl.shouldBeNull()
        event.description!! shouldStartWith "ZEA / ZE4\nArnold de Boer (Niederlande): Gitarre, Gesang"
        event.description shouldContain "Zea greift die Zeit"
    }

    @Test
    fun `reads both prices without the bracketed tiers and keeps the venue's words as a note`() {
        val event = scrape("aniqo")

        event.pricePresale shouldBe BigDecimal("20.00")
        event.priceBoxOffice shouldBe BigDecimal("24.00")
        event.priceNote!! shouldContain "Sozialpreis"
        event.ticketUrl shouldBe "https://rausgegangen.de/events/aniqo-featuring-love-supreme-jonny-0/"
        event.description!! shouldContain "The evening will be opened by Jonny"
    }

    @Test
    fun `keeps free entry as the price note`() {
        val event = scrape("chorlibri")

        event.doorsTime shouldBe LocalTime.of(17, 30)
        event.priceNote shouldBe "Eintritt: frei (Spenden erbeten)"
        event.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `reads the description field and leaves out the link fields`() {
        val event = scrape("mantic-mandala")

        event.description!! shouldStartWith "1 Church, 6 Live sets, 3 Hours of meditative drones"
        event.description shouldNotContain "Links"
    }

    @Test
    fun `keeps a table of paired presale and door tiers as a note only`() {
        val html =
            """<main><h1 class="entry-title">Zeralda</h1><div class="vsel-content"><div class="vsel-acf-eintrittspreis">""" +
                """<span class="acf-field-value">Tickets VVK//AK: 5€ // 8€ (Ermäßigt) 15€ // 18€ (Standard)</span></div></div></main>"""

        val event = scraper.scrape(Jsoup.parse(html), url("zeralda"))!!

        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.priceNote shouldBe "Tickets VVK//AK: 5€ // 8€ (Ermäßigt) 15€ // 18€ (Standard)"
    }

    @Test
    fun `returns null for a page without the event fields`() {
        scraper.scrape(Jsoup.parse("<main><h1 class=\"entry-title\">X</h1></main>"), url("x")).shouldBeNull()
    }

    private fun url(name: String) = "https://galilaea-kirche.de/event/$name/"

    private fun document(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/galilaeakirche/galilaeakirche-event-$name.html")!!
                .bufferedReader()
                .readText(),
            url(name)
        )
}

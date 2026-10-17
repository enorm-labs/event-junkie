package de.norm.events.scraper.schlossparktheater

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class SchlossparkTheaterProductionPageScraperTest {
    private val scraper = SchlossparkTheaterProductionPageScraper()

    @Test
    fun `reads the production text up to the first rule`() {
        val url = "https://www.schlossparktheater.de/produktionen/putsch.html"
        val html = javaClass.classLoader.getResource("scraper/schlossparktheater/schlossparktheater-putsch.html")!!.readText()

        val text = scraper.scrape(Jsoup.parse(html, url))!!

        text shouldStartWith "Was passiert, wenn Satire zur Macht wird?\n\nKlara Milkowski ist witzig"
        text.split("\n\n").size shouldBe 4
        text shouldNotContain "Stroboskop"
        text shouldNotContain "Judith Richter"
    }

    @Test
    fun `a page without the production column yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Nicht gefunden</p></body></html>")).shouldBeNull()
    }
}

package de.norm.events.scraper.bkatheater

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Duration

class BkaTheaterProductionPageScraperTest {
    private val scraper = BkaTheaterProductionPageScraper()

    @Test
    fun `reads the text with its folded part and the doors rule`() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/bkatheater/bkatheater-production.html")!!
                .bufferedReader()
                .readText()

        val production = scraper.scrape(Jsoup.parse(html, "https://www.bka-theater.de/prg/462"))

        val description = production.description.shouldNotBeNull()
        description shouldContain "\n»Das werde ich zu verhindern wissen"
        description shouldEndWith "viele davon auf den Bühnen des Landes, die er ganzjährig bespielt."
        production.doorsLead shouldBe Duration.ofHours(1)
    }

    @Test
    fun `reads nothing from a page without the text block`() {
        val production = scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>"))

        production.description.shouldBeNull()
        production.doorsLead.shouldBeNull()
    }
}

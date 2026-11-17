package de.norm.events.scraper.bkatheater

import de.norm.events.scraper.fixtureDocument
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
        val production = scraper.scrape(fixtureDocument("bkatheater/bkatheater-production.html", "https://www.bka-theater.de/prg/462"))

        val description = production.description.shouldNotBeNull()
        description shouldContain "\n»Das werde ich zu verhindern wissen"
        description shouldEndWith "viele davon auf den Bühnen des Landes, die er ganzjährig bespielt."
        production.doorsLead shouldBe Duration.ofHours(1)
        production.free shouldBe false
    }

    @Test
    fun `reads free entry from the ticket note`() {
        val production = scraper.scrape(fixtureDocument("bkatheater/bkatheater-production-free.html", "https://www.bka-theater.de/prg/234"))

        production.free shouldBe true
        production.description.shouldNotBeNull() shouldContain "Gastroslam"
    }

    @Test
    fun `does not read the companion's free entry as a free production`() {
        val page =
            """
            <div class="bka-container-right"><div class="bka-termine"></div><p class="my-2">
            <p>Bei einem »B« im Schwerbehindertenausweis hat die Begleitperson freien Eintritt.</p>
            </p></div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(page)).free shouldBe false
    }

    @Test
    fun `reads nothing from a page without the text block`() {
        val production = scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>"))

        production.description.shouldBeNull()
        production.doorsLead.shouldBeNull()
        production.free shouldBe false
    }
}

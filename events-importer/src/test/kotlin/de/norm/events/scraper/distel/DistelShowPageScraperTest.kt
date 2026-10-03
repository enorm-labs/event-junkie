package de.norm.events.scraper.distel

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class DistelShowPageScraperTest {
    private val scraper = DistelShowPageScraper()
    private val url = "https://distel-berlin.de/spielplan/event/die-schmerztherapie/"

    @Test
    fun `reads the show text with its running time and the header photo`() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/distel/distel-show.html")!!
                .bufferedReader()
                .readText()
        val show = scraper.scrape(Jsoup.parse(html, url))

        show.description.shouldNotBeNull() shouldStartWith "Die kleinste GroKo aller Zeiten"
        show.description.shouldNotBeNull() shouldEndWith "inklusive 1 Pause"
        show.imageUrl shouldBe "https://distel-berlin.de/assets/images/1/2560-1080-3-programme-a8d62d51.jpg"
    }

    @Test
    fun `reads nothing from a page without the show block`() {
        val show = scraper.scrape(Jsoup.parse("<html><body></body></html>", url))

        show.description.shouldBeNull()
        show.imageUrl.shouldBeNull()
    }
}

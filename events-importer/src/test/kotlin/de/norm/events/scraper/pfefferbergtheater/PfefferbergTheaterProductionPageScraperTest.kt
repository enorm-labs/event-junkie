package de.norm.events.scraper.pfefferbergtheater

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class PfefferbergTheaterProductionPageScraperTest {
    private val url = "https://pfefferberg-theater.de/spielplan/kalender/lesung-fil-01/"

    private val production by lazy {
        PfefferbergTheaterProductionPageScraper().scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/pfefferbergtheater/pfefferbergtheater-production.html")!!
                    .bufferedReader()
                    .readText(),
                url
            )
        )
    }

    @Test
    fun `reads the whole text from the page, not the JSON-LD's cut`() {
        val description = production.description.shouldNotBeNull()
        description shouldStartWith "Ausverkauft! Zusatztermin am 16.12."
        description shouldContain "Fil wuchs im Märkischen Viertel auf"
        description shouldNotContain "Rollstuhlfahrer"
    }

    @Test
    fun `reads the image and the ticket link`() {
        production.imageUrl shouldBe "https://pfefferberg-theater.de/site/assets/files/29934/fil.800x600n.webp"
        production.ticketUrl.shouldNotBeNull() shouldStartWith "https://pfefferberg.billeto.net/"
    }
}

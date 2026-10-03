package de.norm.events.scraper.orania

import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class OraniaDetailPageScraperTest {
    private val scraper = OraniaDetailPageScraper()
    private val url = "https://orania.berlin/oraniaconcerts/event/event/matti-klein-tayfun-schulzke-20261002"

    private fun scrape() =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/orania/orania-detail.html")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )

    @Test
    fun `reads the biography without the teaser the listing already carries`() {
        val description = scrape().shouldNotBeNull().description.shouldNotBeNull()

        description shouldStartWith "The duo of reputed pianist Matti Klein and renowned percussionist Tayfun Schulzke"
        description shouldNotContain "Piano meets Percussion"
    }

    @Test
    fun `reads the full-size photo the thumbnail links to`() {
        scrape().shouldNotBeNull().imageUrl shouldBe
            "https://orania.berlin/fileadmin/Concerts/2025_Herbst/MattiKlein_meets_TayfunSchulzke__c_RubenBauer3.jpg"
    }

    @Test
    fun `leaves the date to the listing and keys the event as the listing does`() {
        val event = scrape().shouldNotBeNull()

        event.title shouldBe "Matti Klein & Tayfun Schulzke"
        event.eventDate shouldBe UNRESOLVED_EVENT_DATE
        event.sourceId shouldBe "orania:matti-klein-tayfun-schulzke-20261002"
    }

    @Test
    fun `returns null for a page without the event block`() {
        scraper.scrape(Jsoup.parse("<html><body><h1>Orania</h1></body></html>", url), url).shouldBeNull()
    }
}

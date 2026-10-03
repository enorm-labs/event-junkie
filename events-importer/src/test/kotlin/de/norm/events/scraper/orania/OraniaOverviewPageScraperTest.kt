package de.norm.events.scraper.orania

import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class OraniaOverviewPageScraperTest {
    private val scraper = OraniaOverviewPageScraper()

    private fun scrape(
        name: String,
        url: String = "https://orania.berlin/concerts"
    ) = scraper.scrape(
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/orania/$name")!!
                .bufferedReader()
                .readText(),
            url
        ),
        url
    )

    @Test
    fun `reads the ten concerts of the first page`() {
        scrape("orania-overview.html") shouldHaveSize 10
    }

    @Test
    fun `reads a concert with every field the listing carries`() {
        val event = scrape("orania-overview.html").first()

        event.title shouldBe "Matti Klein & Tayfun Schulzke"
        event.subtitle shouldBe "Pop, Jazz, Grooves & Melodies - Piano meets Percussion"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 10, 2)
        event.startTime shouldBe LocalTime.of(21, 0)
        event.doorsTime shouldBe null
        event.free shouldBe true
        event.sourceUrl shouldBe "https://orania.berlin/oraniaconcerts/event/event/matti-klein-tayfun-schulzke-20261002"
        event.sourceId shouldBe "orania:matti-klein-tayfun-schulzke-20261002"
        event.imageUrl shouldBe
            "https://orania.berlin/fileadmin/_processed_/f/4/csm_MattiKlein_meets_TayfunSchulzke__c_RubenBauer3_cead2d9a56.jpg"
        event.artists.map { it.name } shouldContainExactly listOf("Matti Klein", "Tayfun Schulzke")
    }

    @Test
    fun `reads an eight o'clock start from the 12-hour clock`() {
        scrape("orania-overview.html").first { it.title == "Hila Kulik" }.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `bills the act, not the programme its title names`() {
        val firstPage = scrape("orania-overview.html")
        firstPage.first { it.title.startsWith("Rossano Snel Duo") }.artists shouldContainExactly
            listOf(ScrapedArtist(name = "Rossano Snel Duo", role = "HEADLINER", titleDerived = true))

        val secondPage = scrape("orania-overview-page-2.html", "https://orania.berlin/concerts/page/2")
        secondPage.first { it.title.startsWith("Rolf Zielke & Hogir") }.artists.map { it.name } shouldContainExactly
            listOf("Rolf Zielke", "Hogir Göregen")
    }

    @Test
    fun `skips the programme notices that carry no series tag`() {
        val events = scrape("orania-overview-page-last.html", "https://orania.berlin/concerts/page/3")

        events shouldHaveSize 5
        events.map { it.title } shouldNotContain "Orania.Concerts Winter Break"
        events.map { it.title } shouldNotContain "Orania.Concerts Holiday Season Special"
    }

    @Test
    fun `keeps the slug of a booking whose URL carries no date`() {
        val event = scrape("orania-overview-page-2.html", "https://orania.berlin/concerts/page/2").first { it.title == "Cosmo Klein & The Campers" }

        event.sourceId shouldBe "orania:cosmo-klein-the-campers"
        event.eventDate shouldBe LocalDate.of(2026, 11, 5)
    }

    @Test
    fun `returns nothing for a page without a listing`() {
        val url = "https://orania.berlin/concerts"
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url) shouldHaveSize 0
    }
}

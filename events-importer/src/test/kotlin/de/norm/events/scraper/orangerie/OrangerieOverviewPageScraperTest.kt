package de.norm.events.scraper.orangerie

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class OrangerieOverviewPageScraperTest {
    private val scraper = OrangerieOverviewPageScraper()
    private val baseUrl = "https://www.orangerie-nk.de/?lang=de"
    private val events = scraper.scrape(Jsoup.parse(fixture("orangerie-home.html"), baseUrl), baseUrl)

    @Test
    fun `reads every night in the programme list`() {
        events shouldHaveSize 16
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2027, 1, 29)
        events.map { it.sourceId }.toSet() shouldHaveSize 16
    }

    @Test
    fun `reads a free DJ night with its card's cover, tags and ticket link`() {
        val night = events.first { it.title == "Sound Canteen w/ Tobi Fries" }

        night.eventType shouldBe EventType.PARTY.name
        night.eventDate shouldBe LocalDate.of(2026, 10, 9)
        night.startTime shouldBe LocalTime.of(20, 0)
        night.sourceId shouldBe "orangerie_neukoelln:2026-10-09-sound-canteen-w-tobi-fries"
        night.free shouldBe true
        night.priceBoxOffice.shouldBeNull()
        night.genre shouldBe "House / Balearic / Leftfield"
        night.imageUrl shouldBe "https://www.orangerie-nk.de/images/events/sound-canteen-w-tobi-fries.jpg"
        night.ticketUrl shouldBe "https://rausgegangen.de/events/sound-canteen-w-tobi-fries-0/"
        night.description!! shouldStartWith "From 20:00–21:30, dinner is accompanied"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Tobi Fries", role = "DJ"))
    }

    @Test
    fun `types a Parkside Sessions night as a concert, tagged or not`() {
        val tagged = events.first { it.title == "Parkside Sessions w/ Jacaré" }
        val untagged = events.first { it.title == "Parkside Sessions w/ Caos Sensible" }

        tagged.eventType shouldBe EventType.CONCERT.name
        tagged.genre shouldBe "Samba"
        tagged.priceBoxOffice shouldBe BigDecimal("10")
        tagged.free shouldBe false
        tagged.artists shouldContainExactly listOf(ScrapedArtist(name = "Jacaré", role = "HEADLINER"))
        untagged.eventType shouldBe EventType.CONCERT.name
        untagged.free shouldBe false
    }

    @Test
    fun `splits a b2b bill and keeps an act whose name has 'and'`() {
        events.first { it.title.endsWith("Bomchello b2b Terje") }.artists.map { it.name } shouldContainExactly listOf("Bomchello", "Terje")
        events.first { it.title.endsWith("juan and only") }.artists.map { it.name } shouldContainExactly listOf("juan and only")
    }

    @Test
    fun `names nobody for a crew or series night`() {
        events.first { it.title.startsWith("Gardens of Disco") }.artists.shouldBeEmpty()
        events.first { it.title.startsWith("Nice Tries") }.artists.shouldBeEmpty()
    }

    @Test
    fun `returns nothing for a page without the programme list`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/orangerie/$name")!!
            .bufferedReader()
            .readText()
}

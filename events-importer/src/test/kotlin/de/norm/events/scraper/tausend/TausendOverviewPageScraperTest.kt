package de.norm.events.scraper.tausend

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TausendOverviewPageScraperTest {
    private val scraper = TausendOverviewPageScraper()
    private val baseUrl = "https://tausendberlin.com/lineup/"
    private val document = Jsoup.parse(fixture("tausend-overview.html"), baseUrl)
    private val events = scraper.scrape(document, baseUrl)

    @Test
    fun `reads every night in the lineup graph`() {
        events shouldHaveSize 18
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2026, 12, 5)
        events.map { it.sourceId }.toSet() shouldHaveSize 18
    }

    @Test
    fun `parses a DJ night with its series, full text, image and Resident Advisor link`() {
        val night = events.first { it.title == "Carla Valenti" }

        night.subtitle shouldBe "Tausend House"
        night.eventType shouldBe EventType.PARTY.name
        night.eventDate shouldBe LocalDate.of(2026, 10, 9)
        night.startTime shouldBe LocalTime.of(21, 0)
        night.endTime.shouldBeNull()
        night.sourceId shouldBe "tausend:2026-10-09-carla-valenti"
        night.sourceUrl shouldBe "https://tausendberlin.com/lineup/#act-carla-valenti-20261009"
        night.ticketUrl shouldBe "https://ra.co/events/2552196"
        night.imageUrl shouldBe "https://tausendberlin.com/wp-content/uploads/2026/10/BarTausend-Dj-CarlaValentini-sw-02.webp"
        night.description!! shouldStartWith "Die chilenische DJ und Produzentin"
        night.description shouldContain "\nValenti lebt heute in Barcelona"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Carla Valenti", role = "DJ"))
        night.status shouldBe "SCHEDULED"
    }

    @Test
    fun `keeps the text the graph's description cuts short`() {
        val night = events.first { it.title == "House Queens" }

        night.description!! shouldContain "Ab 22 Uhr eröffnet A.N.A.I.K.A. die musikalische Nacht"
    }

    @Test
    fun `types a Tausend Live night as a concert with the band as one headliner`() {
        val night = events.first { it.title == "Olla & The Groove Markers" }

        night.eventType shouldBe EventType.CONCERT.name
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Olla & The Groove Markers", role = "HEADLINER"))
    }

    @Test
    fun `splits a shared DJ bill`() {
        val night = events.first { it.subtitle == "Yacht Week Party" }

        night.artists.map { it.name } shouldContainExactly listOf("Bombata", "Florian Kepler", "Mambi Dexter")
    }

    @Test
    fun `puts a 24 H start on the printed night and names nobody for a series without a DJ`() {
        val night = events.first { it.title == "Le Salon Privé" }

        night.eventDate shouldBe LocalDate.of(2026, 10, 16)
        night.startTime shouldBe LocalTime.of(23, 59)
        night.subtitle.shouldBeNull()
        night.artists.shouldBeEmpty()
        night.ticketUrl.shouldBeNull()
    }

    @Test
    fun `keys the English page's text on the same source ids`() {
        val englishUrl = "https://tausendberlin.com/en/lineup/"
        val english = scraper.descriptions(Jsoup.parse(fixture("tausend-overview-en.html"), englishUrl))

        english.keys shouldBe events.map { it.sourceId }.toSet()
        english.getValue("tausend:2026-10-09-carla-valenti")!! shouldStartWith "The Chilean DJ and producer"
    }

    @Test
    fun `returns nothing for a page without a lineup`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/tausend/$name")!!
            .bufferedReader()
            .readText()
}

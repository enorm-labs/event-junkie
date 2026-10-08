package de.norm.events.scraper.slaughterhouse

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class SlaughterhouseOverviewPageScraperTest {
    private val baseUrl = "https://slaughterhouse-berlin.de/konzerte/"
    private val events = SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    private fun on(day: Int) = events.first { it.eventDate == LocalDate.of(2026, 10, day) }

    @Test
    fun `reads one event per entry between the separators`() {
        events.map { it.eventDate.dayOfMonth } shouldBe listOf(10, 16, 17, 30, 31)
        val concert = on(16)
        concert.title shouldBe "Tyske Ludder + To Avoid"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.startTime shouldBe LocalTime.of(22, 0)
        concert.sourceId shouldBe "slaughterhouse:2026-10-16"
        concert.sourceUrl shouldBe baseUrl
        concert.imageUrl shouldBe "https://slaughterhouse-berlin.de/wp-content/uploads/2026/09/image-3.png"
        concert.artists.map { it.name } shouldBe listOf("Tyske Ludder", "To Avoid")
        concert.description!! shouldNotContain "Tickets:"
    }

    @Test
    fun `unwraps a ticket link pasted through Facebook's redirect`() {
        on(16).ticketUrl shouldBe "https://www.tixforgigs.com/Event/67150"
        on(17).ticketUrl shouldBe "https://www.eventim-light.com/de/a/5c8824131eb12100019a7054/"
    }

    @Test
    fun `reads a party's style line and DJs, and its doors`() {
        val party = on(10)
        party.eventType shouldBe EventType.PARTY.name
        party.genre shouldBe "minimal, synth, wave"
        party.artists.map { it.name } shouldBe listOf("marko könig", "andre s.", "hazi")
        val factory = on(31)
        factory.doorsTime shouldBe LocalTime.of(22, 0)
        factory.startTime.shouldBeNull()
        factory.artists.shouldBeEmpty()
        factory.imageUrl shouldBe "https://slaughterhouse-berlin.de/wp-content/uploads/2024/02/image-1.png"
        factory.description!! shouldNotContain "– – –"
    }

    @Test
    fun `bills the band before a title's colon and reads the door price`() {
        val night = on(30)
        night.eventType shouldBe EventType.CONCERT.name
        night.artists.map { it.name } shouldBe listOf("Capper")
        night.priceBoxOffice shouldBe BigDecimal("20.00")
        on(17).artists.map { it.name } shouldBe listOf("Blutgott")
    }

    @Test
    fun `yields nothing for a page without the post`() {
        SlaughterhouseOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/slaughterhouse/slaughterhouse-konzerte.html")!!
            .bufferedReader()
            .readText()
}

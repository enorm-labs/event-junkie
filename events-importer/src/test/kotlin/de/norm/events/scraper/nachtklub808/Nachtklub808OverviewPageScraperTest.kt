package de.norm.events.scraper.nachtklub808

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class Nachtklub808OverviewPageScraperTest {
    private val baseUrl = "https://808.berlin/"
    private val events = Nachtklub808OverviewPageScraper().scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    @Test
    fun `reads every night from the EventList island, dated in Berlin`() {
        events.size shouldBe 24
        events.forEach { it.eventType shouldBe EventType.PARTY.name }
        val friday = events.first { it.eventDate == LocalDate.of(2026, 10, 9) }
        friday.title shouldBe "Heartbreak"
        friday.sourceId shouldBe "nachtklub_808:2026-10-09"
        friday.sourceUrl shouldBe baseUrl
        friday.startTime shouldBe LocalTime.of(23, 0)
        friday.artists.map { it.name } shouldBe listOf("PANA", "RACLEZZ")
        events.first { it.eventDate == LocalDate.of(2026, 10, 31) }.title shouldBe "HALLOWEEN"
    }

    @Test
    fun `bills a host like a DJ`() {
        events.first { it.eventDate == LocalDate.of(2026, 10, 2) }.artists.map { it.name } shouldBe listOf("MAXXX", "NO A\$\$ETS", "GATO", "Ufo361")
    }

    @Test
    fun `strips markup from a summer pop-up name, gives it no club hours and keys a second row on the date apart`() {
        val popUps = events.filter { it.eventDate == LocalDate.of(2026, 8, 8) }

        popUps.map { it.title } shouldBe listOf("808 SUMMER POP UP – ON MY MIND – DAY CLUB", "808 SUMMER POP UP – NIGHT SESSION")
        popUps.map { it.sourceId } shouldBe listOf("nachtklub_808:2026-08-08", "nachtklub_808:2026-08-08-2")
        popUps.map { it.ticketUrl } shouldBe listOf("https://onmymind.ticket.io/wUTlDdKd/", null)
        popUps.forEach { it.startTime shouldBe null }
    }

    @Test
    fun `yields nothing for a page without the island`() {
        Nachtklub808OverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/nachtklub808/nachtklub808-overview.html")!!
            .bufferedReader()
            .readText()
}

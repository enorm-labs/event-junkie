package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PfefferbergHaus13OverviewPageScraperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = PfefferbergHaus13OverviewPageScraper(clock)
    private val listingUrl = "https://haus13.pfefferwerk.de/veranstaltungen/"
    private val events = scraper.scrape(Jsoup.parse(fixture("pfefferberghaus13-overview.html"), listingUrl), listingUrl)

    private fun titled(prefix: String) = events.first { it.title.startsWith(prefix) }

    @Test
    fun `reads the five rows of the first page, dated in the nearest year`() {
        events.map { it.eventDate } shouldBe
            listOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 4))
        val concert = titled("Resonanzen")
        concert.startTime shouldBe LocalTime.of(19, 30)
        concert.sourceUrl shouldBe "https://haus13.pfefferwerk.de/event/resonanzen-internationale-klaenge-rock-den-berg/"
        concert.sourceId shouldBe "pfefferberg_haus_13:resonanzen-internationale-klaenge-rock-den-berg"
        concert.imageUrl shouldBe "https://haus13.pfefferwerk.de/wp-content/uploads/2026/08/Resonanzen-16.10.2026-Final-website.png"
        concert.ticketUrl shouldBe null
    }

    @Test
    fun `types a premiere as a reading and a debate as other, and reads free entry`() {
        titled("Premiere mit Buchpreisträgerin").eventType shouldBe EventType.READING.name
        val debate = titled("Europa kontrovers")
        debate.eventType shouldBe EventType.OTHER.name
        debate.free shouldBe true
        titled("FIREWORK!").ticketUrl shouldBe "https://www.eventbrite.de/e/firework-berlin-annabell-whitney-tickets-1981851019750?aff=oddtdtcreator"
    }

    @Test
    fun `bills acts only from a title that frames them`() {
        val page2Url = "https://haus13.pfefferwerk.de/veranstaltungen/page/2/"
        val page2 = scraper.scrape(Jsoup.parse(fixture("pfefferberghaus13-overview-page2.html"), page2Url), page2Url)

        page2.first { it.title.startsWith("Soul Night") }.artists.map { it.name } shouldBe listOf("Montigo Rim", "Hiddit")
        titled("Resonanzen").artists.shouldBeEmpty()
        titled("FIREWORK!").artists.shouldBeEmpty()
    }

    @Test
    fun `yields nothing for a page without rows`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", listingUrl), listingUrl).shouldBeEmpty()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/pfefferberghaus13/$name")!!
            .bufferedReader()
            .readText()
}

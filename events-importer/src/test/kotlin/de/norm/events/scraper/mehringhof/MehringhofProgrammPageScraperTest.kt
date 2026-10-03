package de.norm.events.scraper.mehringhof

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class MehringhofProgrammPageScraperTest {
    private val scraper = MehringhofProgrammPageScraper()
    private val url = "https://www.mehringhoftheater.de/programm/"

    private fun scrape(name: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/mehringhof/$name")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )

    @Test
    fun `reads the performances and skips the dark days`() {
        val events = scrape("mehringhof-programm.html")

        events.size shouldBe 22
        events.map { it.eventDate.dayOfMonth } shouldContainExactly events.map { it.eventDate.dayOfMonth }.sorted()
    }

    @Test
    fun `reads a row with the year from its ticket link`() {
        val show = scrape("mehringhof-programm.html").first { it.eventDate == LocalDate.of(2026, 10, 2) }

        show.title shouldBe "Hinnerk Köhn mit NOIR (Berlin Premiere)"
        show.eventType shouldBe "COMEDY"
        show.startTime shouldBe LocalTime.of(20, 0)
        show.sourceId shouldBe "mehringhof:94723"
        show.ticketUrl shouldBe show.sourceUrl
        show.soldOut shouldBe true
        show.artists.map { it.name } shouldContainExactly listOf("Hinnerk Köhn")
    }

    @Test
    fun `reads plenty of tickets as not sold out and bills no act for a series`() {
        val flash = scrape("mehringhof-programm.html").first { it.eventDate == LocalDate.of(2026, 10, 3) }
        val funFacts = scrape("mehringhof-programm-dezember.html").first { it.title.startsWith("FUN FACTS") }

        flash.soldOut shouldBe false
        funFacts.artists.shouldBeEmpty()
    }

    @Test
    fun `keys a show row without a shop link by its date and clock`() {
        val show = scrape("mehringhof-programm.html").first { it.eventDate == LocalDate.of(2026, 10, 11) }

        show.title shouldBe "Dota Kehr & Regis Damasceno - Liederabend"
        show.sourceId shouldBe "mehringhof:2026-10-11-18"
        show.ticketUrl shouldBe null
        show.sourceUrl shouldBe url
    }
}

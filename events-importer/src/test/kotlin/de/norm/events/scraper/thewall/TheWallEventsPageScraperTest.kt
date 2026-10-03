package de.norm.events.scraper.thewall

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TheWallEventsPageScraperTest {
    private val scraper = TheWallEventsPageScraper()
    private val url = "https://thewallcomedy.com/venues/thewallcomedy/events/"

    private fun scrape(name: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/thewall/$name")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )

    @Test
    fun `reads the twelve events of the first page`() {
        scrape("thewall-events.html") shouldHaveSize 12
    }

    @Test
    fun `maps a showcase from its JSON-LD`() {
        val show = scrape("thewall-events.html").first { it.title == "On Fire ! Scorching Stand Up Comedy!" }

        show.eventType shouldBe "COMEDY"
        show.eventDate shouldBe LocalDate.of(2026, 10, 8)
        show.startTime shouldBe LocalTime.of(20, 0)
        show.endDate shouldBe LocalDate.of(2026, 10, 8)
        show.endTime shouldBe LocalTime.of(22, 0)
        show.sourceUrl shouldBe "https://thewallcomedy.com/events/2026-10-08-the-wall-comedy-on-fire-scorching-stand-up-comedy/"
        show.sourceId shouldBe "the_wall:2026-10-08-the-wall-comedy-on-fire-scorching-stand-up-comedy"
        show.imageUrl.shouldNotBeNull() shouldStartWith "https://spotagig.eu/media/"
        show.description.shouldNotBeNull() shouldStartWith "On Fire!"
        show.status shouldBe "SCHEDULED"
    }

    @Test
    fun `reads the clock as Berlin time, ignoring the +00 00 offset`() {
        scrape("thewall-events.html").first { it.title == "Meanwhile in Berlin.." }.startTime shouldBe LocalTime.of(20, 45)
    }

    @Test
    fun `keeps a guest producer as promoter and drops the club's own productions`() {
        val events = scrape("thewall-events.html")

        events.first { it.title.startsWith("The Kee News") }.promoters shouldContainExactly listOf("The Kee Comedy")
        events.first { it.title.startsWith("SASHA NEZLOBIN") }.promoters.shouldBeEmpty()
    }

    @Test
    fun `reads a partial page`() {
        val events = scrape("thewall-events-page-last.html")

        events shouldHaveSize 3
        events.first().eventDate shouldBe LocalDate.of(2027, 1, 16)
    }

    @Test
    fun `returns nothing for a page without JSON-LD`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeEmpty()
    }
}

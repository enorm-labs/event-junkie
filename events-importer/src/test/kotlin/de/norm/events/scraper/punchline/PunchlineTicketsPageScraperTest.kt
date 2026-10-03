package de.norm.events.scraper.punchline

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

class PunchlineTicketsPageScraperTest {
    private val scraper = PunchlineTicketsPageScraper()
    private val url = "https://punchlineberlin.com/de/tickets"

    private val events by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/punchline/punchline-tickets.html")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )
    }

    @Test
    fun `reads every date of the payload`() {
        events shouldHaveSize 27
        events.size shouldBe events.map { it.sourceId }.distinct().size
    }

    @Test
    fun `reads a date in Berlin time with its show and acts`() {
        val show = events.first()

        show.title shouldBe "Mission Erde Live"
        show.subtitle shouldBe "Robert Marc Lehmann"
        show.eventType shouldBe "COMEDY"
        show.eventDate shouldBe LocalDate.of(2026, 10, 3)
        show.startTime shouldBe LocalTime.of(20, 0)
        show.description.shouldNotBeNull() shouldStartWith "Am 3. & 4. Oktober 2026"
        show.sourceUrl shouldBe "https://punchlineberlin.com/de/shows/mission-erde-live"
        show.sourceId shouldBe "punchline:mission-erde-live-2026-10-03-20"
        show.artists.map { it.name } shouldContainExactly listOf("Robert Marc Lehmann")
    }

    @Test
    fun `resolves a later date's reference to its show page`() {
        events[1].sourceUrl shouldBe "https://punchlineberlin.com/de/shows/mission-erde-live"
        events[1].eventDate shouldBe LocalDate.of(2026, 10, 4)
    }

    @Test
    fun `reads the doors from a date's note`() {
        events.first { it.doorsTime != null }.doorsTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `types a band or an unplugged set as a concert`() {
        events.filter { it.eventType == "CONCERT" }.map { it.subtitle }.distinct() shouldContainExactly
            listOf("Andreas Gabalier", "The Capital Dance Orchestra feat. Sharon Brauner & Meta Hüper", "Gregor Meyle & Band")
    }

    @Test
    fun `reads nothing from a page without the payload`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url).shouldBeEmpty()
    }
}

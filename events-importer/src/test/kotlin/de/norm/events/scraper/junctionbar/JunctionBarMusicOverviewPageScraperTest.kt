package de.norm.events.scraper.junctionbar

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [JunctionBarMusicOverviewPageScraper].
 *
 * Parses the saved July 2026 monthly program snapshot. The year comes from the page URL's
 * `07_2026` month folder, so no clock is needed — dates are fully deterministic.
 */
class JunctionBarMusicOverviewPageScraperTest {
    private val baseUrl = "https://www.junction-bar.de/program/07_2026/07_26.html"
    private val scraper = JunctionBarMusicOverviewPageScraper()

    private fun program() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/junctionbar/junctionbar-music-07_2026.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private val events by lazy { scraper.scrape(program(), baseUrl) }

    @Test
    fun `parses every dated night and skips the PRIVAT PARTY nights`() {
        // 10 dated nights; the two "PRIVAT PARTY" nights (4.7, 11.7) carry no real band and are dropped.
        events shouldHaveSize 8
    }

    @Test
    fun `extracts all fields of a single-act concert`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 3) }

        event.title shouldBe "Funkverband"
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 7, 3)
        // 3 July 2026 is a Friday → weekend showtime 22:00.
        event.startTime shouldBe LocalTime.of(22, 0)
        event.genre shouldBe "funk"
        event.imageUrl shouldBe "https://www.junction-bar.de/program/11_25/Funkverband.square.jpg"
        event.sourceUrl shouldBe baseUrl
        event.ticketUrl shouldBe "https://junction-bar-shop.de/funkverband.html"
        // The ticket-shop page slug is the stable canonical identity.
        event.sourceId shouldBe "junction_bar:funkverband"
        event.description!! shouldContain "Funkverband ist richtig gut"
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "Funkverband", role = "HEADLINER"))
    }

    @Test
    fun `combines co-billed acts and reads an explicit showtime override`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 23) }

        // "three acts tonight" → all three acts co-billed as headliners.
        event.title shouldBe "JUAKALI + fragFrank! + Icarus Burns"
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "JUAKALI", role = "HEADLINER"),
                ScrapedArtist(name = "fragFrank!", role = "HEADLINER"),
                ScrapedArtist(name = "Icarus Burns", role = "HEADLINER")
            )
        // The date bar carries an explicit "---- 20:30 ----" override, winning over the weekday default.
        event.startTime shouldBe LocalTime.of(20, 30)
        // The "three acts tonight" marker is not a genre; the first band's style tag is used instead.
        event.genre shouldBe "Alternative/Punk"
        event.ticketUrl shouldBe "https://junction-bar-shop.de/juakali-fragFrank-icarus-burns.html"
    }

    @Test
    fun `uses the weekday default showtime and a band style tag for a two-act Thursday`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 9) }

        event.title shouldBe "How to make Friends + teenweek"
        // 9 July 2026 is a Thursday → weekday showtime 21:00.
        event.startTime shouldBe LocalTime.of(21, 0)
        event.genre shouldBe "Indie Pop"
    }

    @Test
    fun `falls back to a synthesized sourceId and null genre when a night has no ticket link or style tag`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 31) }

        event.title shouldBe "Go(ø)d Trip + Yung Street"
        event.genre.shouldBeNull()
        event.ticketUrl shouldBe "https://junction-bar-shop.de/good-trip-yung-street.html"
    }

    @Test
    fun `returns no events for a URL without a month folder`() {
        scraper.scrape(program(), "https://www.junction-bar.de/index.html").shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without a program`() {
        val emptyDoc = Jsoup.parse("<html><body><p>Baustelle</p></body></html>", baseUrl)
        scraper.scrape(emptyDoc, baseUrl).shouldBeEmpty()
    }

    @Test
    fun `drops a format phrase from the act and keeps the billing as the title`() {
        // October 2026 page: `LA RONY <span>acoustic set</span> <span>Soul, Rnb, Pop</span>` (#2208).
        val octoberUrl = "https://www.junction-bar.de/program/10_2026/10_26.html"
        val october =
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/junctionbar/junctionbar-music-10_2026.html")!!
                    .bufferedReader()
                    .readText(),
                octoberUrl
            )
        val night = scraper.scrape(october, octoberUrl).single { it.eventDate == LocalDate.of(2026, 10, 1) }

        night.title shouldBe "LA RONY acoustic set"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "LA RONY", role = "HEADLINER"))
    }
}

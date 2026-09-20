package de.norm.events.scraper.privatclub

import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for [PrivatclubOverviewPageScraper].
 *
 * Uses a static HTML fixture that mirrors the real Privatclub WordPress
 * page structure for deterministic, offline-safe testing.
 */
class PrivatclubOverviewPageScraperTest {
    private val scraper = PrivatclubOverviewPageScraper()
    private val baseUrl = "https://privatclub-berlin.de/"
    private lateinit var html: String

    @BeforeEach
    fun setUp() {
        html =
            javaClass.classLoader
                .getResourceAsStream("scraper/privatclub/privatclub-overview.html")!!
                .bufferedReader()
                .readText()
    }

    @Test
    fun `scrape extracts all events from fixture`() {
        val document = Jsoup.parse(html, baseUrl)
        val events = scraper.scrape(document, baseUrl)

        events shouldHaveSize 6
    }

    @Nested
    inner class ConcertParsing {
        @Test
        fun `parses concert with full data`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val concert = events.first { it.title == "Sean Rowe" }

            concert.eventDate shouldBe LocalDate.of(2026, 5, 16)
            concert.doorsTime shouldBe LocalTime.of(19, 0)
            concert.startTime shouldBe LocalTime.of(20, 0)
            concert.eventType shouldBe "CONCERT"
            concert.genre shouldBe "Folk, Indie"
            concert.sourceUrl shouldBe "https://privatclub-berlin.de/event/sean-rowe-2/"
            concert.sourceId shouldBe "privatclub:sean-rowe-2"
            concert.ticketUrl shouldBe "https://www.koka36.de/event_site.php?event=170892"
            concert.imageUrl shouldBe "https://privatclub-berlin.de/wp-content/uploads/2025/10/sean-rowe.jpeg"
            concert.soldOut shouldBe false
            concert.status shouldBe "SCHEDULED"
            concert.description shouldContain "American singer-songwriter"
            concert.description shouldContain "five full-length albums"
        }

        @Test
        fun `parses concert with support act in subtitle`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val concert = events.first { it.title == "Sandra Hesch" }

            concert.subtitle shouldBe "Liebling(s)tour 2026 | Support: Luana"
            concert.eventDate shouldBe LocalDate.of(2026, 5, 28)
            concert.doorsTime shouldBe LocalTime.of(18, 30)
            concert.startTime shouldBe LocalTime.of(19, 30)
            concert.ticketUrl shouldBe "https://www.eventim.de/sandra-hesch-berlin"
            concert.artists shouldContainExactly
                listOf(
                    ScrapedArtist(name = "Sandra Hesch", role = "HEADLINER", titleDerived = true),
                    ScrapedArtist(name = "Luana", role = "SUPPORT")
                )
        }

        @Test
        fun `extracts title as headliner from concert without support line`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val concert = events.first { it.title == "Sean Rowe" }

            // CONCERT type confirms the title is the headliner, even with no "Support:" line
            concert.artists shouldContainExactly listOf(ScrapedArtist(name = "Sean Rowe", role = "HEADLINER", titleDerived = true))
        }
    }

    @Nested
    inner class PartyParsing {
        @Test
        fun `parses party event with genre and complex pricing`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val party = events.first { it.title == "This Charming Man" }

            party.eventDate shouldBe LocalDate.of(2026, 5, 16)
            party.startTime shouldBe LocalTime.of(23, 0)
            party.eventType shouldBe "PARTY"
            party.genre shouldBe "Indie Pop"
            party.subtitle shouldBe "Indie Classics from the Past till Now mit DJ Spencer"
            party.sourceId shouldBe "privatclub:this-charming-man-2-3"
            party.soldOut shouldBe false
            party.status shouldBe "SCHEDULED"
            // Complex pricing "4€ - ab 24h 6€" stored as note
            party.priceNote shouldBe "4€ - ab 24h 6€"
        }

        @Test
        fun `party events do not extract artists`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val party = events.first { it.title == "This Charming Man" }

            party.artists.shouldBeEmpty()
        }
    }

    @Nested
    inner class StatusHandling {
        @Test
        fun `detects sold out status`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val event = events.first { it.title == "Rita Dakota" }

            event.soldOut shouldBe true
            event.status shouldBe "SCHEDULED"
        }

        @Test
        fun `detects rescheduled status`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val event = events.first { it.title == "TELENOVA" }

            event.status shouldBe "POSTPONED"
            event.soldOut shouldBe false
            event.subtitle shouldBe "The Warning Tour"
        }

        @Test
        fun `detects cancelled status`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val event = events.first { it.title == "Velvet Wasted" }

            event.status shouldBe "CANCELLED"
            event.soldOut shouldBe false
        }
    }

    @Nested
    inner class PriceParsing {
        @Test
        fun `parses AK price as box office price`() {
            val document = Jsoup.parse(html, baseUrl)
            val events = scraper.scrape(document, baseUrl)
            val event = events.first { it.title == "Sandra Hesch" }

            event.priceBoxOffice shouldBe BigDecimal("35")
        }
    }

    @Nested
    inner class DateYearRollover {
        @Test
        fun `parseDateFromHtml assigns next year when month-day is in the past`() {
            // Clock fixed to December 15, 2026 — a "Januar" date should resolve to January 2027
            val december2026 =
                Clock.fixed(
                    ZonedDateTime.of(2026, 12, 15, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault()
                )
            val rolloverScraper = PrivatclubOverviewPageScraper(clock = december2026)

            // Minimal HTML with no JSON-LD sibling, forcing the HTML date fallback.
            // The date "Fr. 10." + "Januar" is in the past relative to Dec 15, 2026,
            // so parseDateFromHtml should roll it forward to 2027-01-10.
            val minimalHtml =
                """
                <div class="event_wrapper skewed">
                    <a class="event_header" href="/event/test-rollover/">
                        <span class="titel">Rollover Test</span>
                        <span class="datum_part1">Fr. 10.</span>
                        <span class="datum_part2">Januar</span>
                        <span class="event_typ">Konzert</span>
                    </a>
                </div>
                """.trimIndent()

            val document = Jsoup.parse(minimalHtml, baseUrl)
            val events = rolloverScraper.scrape(document, baseUrl)

            events shouldHaveSize 1
            events.first().eventDate shouldBe LocalDate.of(2027, 1, 10)
        }

        @Test
        fun `parseDateFromHtml keeps current year when month-day is in the future`() {
            // Clock fixed to March 1, 2026 — a "Mai" date should stay in 2026
            val march2026 =
                Clock.fixed(
                    ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault()
                )
            val futureScraper = PrivatclubOverviewPageScraper(clock = march2026)

            val minimalHtml =
                """
                <div class="event_wrapper skewed">
                    <a class="event_header" href="/event/test-future/">
                        <span class="titel">Future Test</span>
                        <span class="datum_part1">Sa. 16.</span>
                        <span class="datum_part2">Mai</span>
                        <span class="event_typ">Konzert</span>
                    </a>
                </div>
                """.trimIndent()

            val document = Jsoup.parse(minimalHtml, baseUrl)
            val events = futureScraper.scrape(document, baseUrl)

            events shouldHaveSize 1
            events.first().eventDate shouldBe LocalDate.of(2026, 5, 16)
        }
    }

    @Test
    fun `returns empty list for empty page`() {
        val emptyHtml = "<html><body><section class='programm'></section></body></html>"
        val document = Jsoup.parse(emptyHtml, baseUrl)
        val events = scraper.scrape(document, baseUrl)

        events shouldHaveSize 0
    }
}

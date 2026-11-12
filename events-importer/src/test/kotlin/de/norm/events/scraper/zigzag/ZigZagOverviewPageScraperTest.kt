package de.norm.events.scraper.zigzag

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Unit tests for [ZigZagOverviewPageScraper], against a snapshot taken on 2026-10-01. */
class ZigZagOverviewPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val sourceUrl = "https://www.zigzag-jazzclub.berlin/programmneu"
    private val programme = "zigzag/zigzag-programm.html"
    private val events = ZigZagOverviewPageScraper(EventSource.ZIG_ZAG_JAZZ_CLUB, clock).scrape(fixtureDocument(programme, sourceUrl), sourceUrl)
    private val hall = ZigZagOverviewPageScraper(EventSource.ZIG_ZAG_HALL, clock).scrape(fixtureDocument(programme, sourceUrl), sourceUrl)

    private fun on(date: LocalDate): ScrapedEvent = events.single { it.eventDate == date }

    @Test
    fun `parses the club's nights and leaves out the hall's`() {
        // 56 items: 20 are the Zig Zag Hall's, and the featured one is yesterday's.
        events shouldHaveSize 35
        events.map { it.sourceId }.distinct() shouldHaveSize 35
        events.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 1)
        events.maxOf { it.eventDate } shouldBe LocalDate.of(2026, 12, 19)
        events.filter { it.title.startsWith("ZIG ZAG HALL", ignoreCase = true) }.shouldBeEmpty()
        events.map { it.eventType }.distinct() shouldBe listOf(EventType.CONCERT.name)
    }

    @Test
    fun `parses a fully populated item`() {
        val night = on(LocalDate.of(2026, 10, 1))

        night.title shouldBe "Roland Satterwhite Quartet feat. Olivia Trummer"
        night.genre shouldBe "Jazz"
        night.sourceUrl shouldBe "https://www.zigzag-jazzclub.berlin/program-mai/rsgolivi"
        night.sourceId shouldBe "zig_zag_jazz_club:rsgolivi"
        night.imageUrl!! shouldStartWith "https://images.squarespace-cdn.com/"
        night.artists.map { it.name } shouldBe listOf("Roland Satterwhite Quartet", "Olivia Trummer")
    }

    @Test
    fun `reads the style from the bracketed first line of the excerpt`() {
        on(LocalDate.of(2026, 10, 2)).genre shouldBe "Soul, Jazz"
        on(LocalDate.of(2026, 10, 6)).genre shouldBe "Jazz / Funk / Groove / Swing"
    }

    @Test
    fun `names the act before a programme name`() {
        on(LocalDate.of(2026, 10, 8)).artists.map { it.name } shouldBe listOf("Mirna Bogdanovic")
        on(LocalDate.of(2026, 10, 16)).artists.map { it.name } shouldBe listOf("Sari Schorr")
    }

    @Test
    fun `names no act for the jam session or a tribute night`() {
        on(LocalDate.of(2026, 10, 6)).artists.shouldBeEmpty()
        on(LocalDate.of(2026, 11, 27)).artists.shouldBeEmpty()
        on(LocalDate.of(2026, 10, 4)).artists.shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Event 12346: the page bills Sergio Medina and his band.
            "Celebrating Buena Vista Social Club",
            // Event 12347: the page bills Atrin Madani, Martina Barta and their trio.
            "Golden Voices: Sinatra, Sammy Davis Jr., Tony Bennett & Beyond",
            // Event 12837: the page bills the five musicians.
            "The Jazz Crusaders and Beyond",
            // Event 13835: the house's own party, whose page bills Mike Russell's band.
            "Zig Zag Silvesterparty - Mike Russells Funky Soul Kitchen"
        ]
    )
    fun `names no act for a night that celebrates an honoree or is the house's party`(title: String) {
        singleNight(title).artists.shouldBeEmpty()
    }

    @Test
    fun `keeps the act of a title that ends in a word ending in and, then Beyond`() {
        singleNight("Thousand Beyond").artists.map { it.name } shouldBe listOf("Thousand Beyond")
    }

    private fun singleNight(title: String): ScrapedEvent {
        val html =
            """
            <div class="summary-item summary-item-record-type-event">
              <a href="/program-mai/x" class="summary-title-link">$title</a>
              <time class="summary-metadata-item summary-metadata-item--date">November 12, 2026</time>
            </div>
            """.trimIndent()
        return ZigZagOverviewPageScraper(EventSource.ZIG_ZAG_JAZZ_CLUB, clock)
            .scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
            .single()
    }

    @Test
    fun `reads the style when the blurb runs straight on from it`() {
        // The Hiromi Trio's excerpt is one paragraph: "(Jazz)Von Chick Corea entdeckt, …".
        hall.single { it.eventDate == LocalDate.of(2026, 11, 5) }.genre shouldBe "Jazz"
    }

    @Test
    fun `keeps only the hall's items for the hall, without their prefix`() {
        hall shouldHaveSize 20
        hall.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 9)
        hall.maxOf { it.eventDate } shouldBe LocalDate.of(2026, 12, 2)
        hall.filter { it.title.startsWith("ZIG ZAG", ignoreCase = true) }.shouldBeEmpty()

        val night = hall.first()
        night.title shouldBe "Jason Moran Plays Duke Ellington"
        night.sourceId shouldBe "zig_zag_hall:jasmorduk"
        night.genre shouldBe "Jazz"
        night.artists.map { it.name } shouldBe listOf("Jason Moran")
    }

    @Test
    fun `names the act before what it plays or celebrates`() {
        hall.single { it.eventDate == LocalDate.of(2026, 10, 30) }.artists.map { it.name } shouldBe listOf("Kurt Elling & The Yellowjackets")
    }

    @Test
    fun `returns nothing for a page without items`() {
        ZigZagOverviewPageScraper(EventSource.ZIG_ZAG_JAZZ_CLUB, clock)
            .scrape(Jsoup.parse("<html><body></body></html>", sourceUrl), sourceUrl)
            .shouldBeEmpty()
    }

    @Test
    fun `skips an item whose date is unreadable`() {
        val html =
            """
            <div class="summary-item summary-item-record-type-event">
              <a href="/program-mai/x" class="summary-title-link">Trio</a>
              <time class="summary-metadata-item summary-metadata-item--date">soon</time>
            </div>
            """.trimIndent()
        ZigZagOverviewPageScraper(EventSource.ZIG_ZAG_JAZZ_CLUB, clock).scrape(Jsoup.parse(html, sourceUrl), sourceUrl).shouldBeEmpty()
    }
}

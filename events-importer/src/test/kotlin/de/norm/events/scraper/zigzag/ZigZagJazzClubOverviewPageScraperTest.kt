package de.norm.events.scraper.zigzag

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Unit tests for [ZigZagJazzClubOverviewPageScraper], against a snapshot taken on 2026-10-01. */
class ZigZagJazzClubOverviewPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val sourceUrl = "https://www.zigzag-jazzclub.berlin/programmneu"
    private val events = ZigZagJazzClubOverviewPageScraper(clock).scrape(Jsoup.parse(fixture(), sourceUrl), sourceUrl)

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/zigzag/zigzag-programm.html")!!
            .bufferedReader()
            .readText()

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

    @Test
    fun `returns nothing for a page without items`() {
        ZigZagJazzClubOverviewPageScraper(clock)
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
        ZigZagJazzClubOverviewPageScraper(clock).scrape(Jsoup.parse(html, sourceUrl), sourceUrl).shouldBeEmpty()
    }
}

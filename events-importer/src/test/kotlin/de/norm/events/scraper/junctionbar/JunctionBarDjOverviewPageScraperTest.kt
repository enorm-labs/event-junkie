package de.norm.events.scraper.junctionbar

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Unit tests for [JunctionBarDjOverviewPageScraper].
 *
 * Uses a fixed clock (2026-06-01) — before the snapshot's earliest date (5.6.) — so
 * weekday-based year inference stays deterministic.
 */
class JunctionBarDjOverviewPageScraperTest {
    private val baseUrl = "https://www.junction-bar.de/DJ_html/DJ.html"

    // Pin the clock before the snapshot's earliest date (5.6.2026) so every night counts as
    // upcoming and the year inferred from the year-less "DD.MM." dates (via weekday) is deterministic.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = JunctionBarDjOverviewPageScraper(clock)

    private fun program() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/junctionbar/junctionbar-dj.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private val events by lazy { scraper.scrape(program(), baseUrl) }

    @Test
    fun `parses every dated night and skips the PRIVAT PARTY nights`() {
        // 13 dated nights; the five "PRIVAT PARTY" nights carry no public act and are dropped.
        events shouldHaveSize 8
    }

    @Test
    fun `extracts all fields of a representative DJ night`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 6, 5) }

        event.title shouldBe "DJane B.B."
        // The "black music & classics with" theme line becomes the subtitle (not a normalizable genre).
        event.subtitle shouldBe "black music & classics"
        event.eventType shouldBe EventType.PARTY.name
        // "5.6. fri" with no year → 2026 (5 June falls on a Friday nearest today).
        event.eventDate shouldBe LocalDate.of(2026, 6, 5)
        // "24:00" is midnight at the end of the dated night, not the start of its morning (#2313).
        event.startTime shouldBe LocalTime.of(23, 59)
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "junction_bar:dj-2026-06-05-djane-b-b"
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "DJane B.B.", role = "DJ"))
    }

    @Test
    fun `reads a non-midnight start time`() {
        // "3.7. fri 23:00".
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 3) }
        event.startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `returns no events for a page without a program`() {
        val emptyDoc = Jsoup.parse("<html><body><p>Baustelle</p></body></html>", baseUrl)
        scraper.scrape(emptyDoc, baseUrl).shouldBeEmpty()
    }
}

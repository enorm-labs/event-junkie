package de.norm.events.scraper.duncker

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
 * Unit tests for [DunckerOverviewPageScraper].
 *
 * Uses a fixed clock (2026-07-01) — before the snapshot's earliest date (03.07.) — so
 * weekday-based year inference stays deterministic. The scraper returns every dated row
 * as-is; dropping past-dated events is the persistence layer's concern (`EventUpsertService`).
 */
class DunckerOverviewPageScraperTest {
    private val baseUrl = "https://www.dunckerclub.de/start.html"

    // Pin the clock before the snapshot's earliest date (03.07.2026) so every event counts as
    // upcoming and the year inferred from the year-less "DD.MM." dates (via weekday) is deterministic.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC)
    private val scraper = DunckerOverviewPageScraper(clock)

    private fun programme() =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/duncker/duncker-overview.html")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    private val events by lazy { scraper.scrape(programme(), baseUrl) }

    @Test
    fun `parses every dated programme row and skips the flyer banner rows`() {
        // 15 dated events; the three leading flyer-banner rows (no date, no event name) are skipped.
        events shouldHaveSize 15
    }

    @Test
    fun `extracts all fields of a representative party night`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 3) }

        event.title shouldBe "Das neue Partymaß 80-90-00"
        event.subtitle shouldBe "Das Beste aus den 80s - 00s"
        // Every Duncker listing is a resident DJ dance night.
        event.eventType shouldBe EventType.PARTY.name
        // "Fr 03.07." with no year → 2026 (3 July falls on a Friday nearest today).
        event.eventDate shouldBe LocalDate.of(2026, 7, 3)
        // "21h-05h" is the night's opening hours, not doors.
        event.doorsTime shouldBe null
        event.startTime shouldBe LocalTime.of(21, 0)
        event.endDate shouldBe LocalDate.of(2026, 7, 4)
        event.endTime shouldBe LocalTime.of(5, 0)
        event.imageUrl shouldBe "https://www.dunckerclub.de/bilder/2026-07-03.jpg"
        // No per-event pages on this single-page site.
        event.sourceUrl shouldBe baseUrl
        event.ticketUrl shouldBe "https://www.facebook.com/events/2032485484004951"
        // The Facebook event id is the stable canonical identity.
        event.sourceId shouldBe "duncker:2032485484004951"
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "WhamPee", role = "DJ"))
        event.promoters.shouldBeEmpty()
    }

    @Test
    fun `strips a presenter link from the title, captures it as a promoter, and splits multiple DJs`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 7, 27) }

        // The embedded "TIQ" presenter link is dropped from the name and surfaced as a promoter.
        event.title shouldBe "Dark Mønday"
        event.promoters shouldContainExactly listOf("TIQ")
        // "Djs Neue K & Lichene" → two DJ acts, label stripped.
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Neue K", role = "DJ"),
                ScrapedArtist(name = "Lichene", role = "DJ")
            )
        event.genre shouldBe "EBM, Post-Punk"
    }

    @Test
    fun `reads genre, opening hours and the DJ from a set-named Dark Mønday row`() {
        // Dark Mønday | darkwave, ebm, postpunk & industrial | DJ Hanzel: Efetto Notte | 22h-04h
        val event = events.first { it.eventDate == LocalDate.of(2026, 9, 21) }

        event.title shouldBe "Dark Mønday"
        event.genre shouldBe "Darkwave, EBM, Post-Punk, Industrial"
        event.doorsTime shouldBe null
        event.startTime shouldBe LocalTime.of(22, 0)
        event.endDate shouldBe LocalDate.of(2026, 9, 22)
        event.endTime shouldBe LocalTime.of(4, 0)
        // "Efetto Notte" is the set name, not a second DJ.
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "Hanzel", role = "DJ"))
    }

    @Test
    fun `recovers events from the malformed table rows`() {
        // Two rows in the snapshot have a stray self-closed `<tr></tr>` before their cells;
        // both must still be parsed (06.07. Dark Mønday, 25.07. Independent Tanzmusik).
        val darkMonday = events.first { it.eventDate == LocalDate.of(2026, 7, 6) }
        darkMonday.title shouldBe "Dark Mønday"
        darkMonday.artists shouldContainExactly listOf(ScrapedArtist(name = "Boris", role = "DJ"))

        val independent = events.first { it.eventDate == LocalDate.of(2026, 7, 25) }
        independent.title shouldBe "Independent Tanzmusik"
        independent.artists shouldContainExactly listOf(ScrapedArtist(name = "Spy", role = "DJ"))
    }

    @Test
    fun `keeps only known genres from a prose style line`() {
        val event = events.first { it.eventDate == LocalDate.of(2026, 10, 16) }

        event.subtitle shouldBe "80s Party & Die Ärzte"
        // The band name stays in the subtitle and never becomes a genre tag.
        event.genre shouldBe "80s"
        // The Facebook link is commented out, so the DJ is a bare text node.
        event.artists shouldContainExactly listOf(ScrapedArtist(name = "WhamPee", role = "DJ"))
    }

    @Test
    fun `returns no events for a page without the programme table`() {
        val emptyDoc = Jsoup.parse("<html><body><p>Baustelle</p></body></html>", baseUrl)
        scraper.scrape(emptyDoc, baseUrl).shouldBeEmpty()
    }
}

package de.norm.events.scraper.gartn

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [GartnOverviewPageScraper].
 *
 * Uses a real page snapshot pinned to a fixed clock, since the programme prints no year. The rest
 * of these tests exist because Carrd gives the parser nothing semantic to hold on to: an event is
 * recognized by its date-shaped heading, its paragraphs are told apart by content rather than
 * position, and `<sup>` carries three different meanings within one lineup.
 */
class GartnOverviewPageScraperTest {
    /** Pinned to the fixture's capture date so the weekday-based year inference is stable. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val scraper = GartnOverviewPageScraper(clock)
    private val baseUrl = "https://www.gartn.xyz/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)
    }

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/gartn/gartn-overview.html")!!
            .bufferedReader()
            .readText()

    private fun event(title: String): ScrapedEvent = events.first { it.title == title }

    @Test
    fun `tags every night Techno, the club's sound, since the venue names no style`() {
        events.map { it.genre }.distinct() shouldBe listOf("Techno")
    }

    @Test
    fun `extracts every dated block and no other container`() {
        // The page's image and footer containers carry the same `.container-component` class; only
        // the ten with a `SA 08.08.` heading are events.
        events shouldHaveSize 10
        events.map { it.eventDate } shouldContainExactly
            listOf(
                LocalDate.of(2026, 8, 8),
                LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 22),
                LocalDate.of(2026, 8, 23),
                LocalDate.of(2026, 8, 29),
                LocalDate.of(2026, 8, 30),
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 6)
            )
    }

    @Test
    fun `parses a fully populated night`() {
        val night = event("OUT OF OFFICE")
        night.eventDate shouldBe LocalDate.of(2026, 8, 8)
        night.eventType shouldBe "PARTY"
        night.subtitle.shouldBeNull()
        night.startTime shouldBe LocalTime.of(14, 0)
        night.sourceUrl shouldBe baseUrl
        night.sourceId shouldBe "gartn:2026-08-08-out-of-office"
        night.ticketUrl shouldBe "https://ra.co/events/2486819"
        night.priceNote.shouldBeNull()
        night.soldOut shouldBe false
        night.free shouldBe false
        night.artists.map { it.name } shouldContainExactly
            listOf("Running Hot", "The Office", "Nalamazon", "Amina", "Luqqi")
        night.artists.map { it.role }.toSet() shouldContainExactly setOf("DJ")
    }

    @Test
    fun `reads the year off the stated weekday, rolling into the next month`() {
        // The headings print `SA 05.09.` with no year; 5 September falls on a Saturday in 2026.
        event("AROMA").eventDate shouldBe LocalDate.of(2026, 9, 5)
    }

    @Test
    fun `splits a two-line heading into title and host subtitle`() {
        val night = event("SONNTAGS IM gART.n")
        night.eventDate shouldBe LocalDate.of(2026, 8, 16)
        night.subtitle shouldBe "by JUDITH VAN WATERKANT"
        // The same series runs three times with a different host; the date keeps the ids apart.
        events.filter { it.title == "SONNTAGS IM gART.n" }.map { it.subtitle } shouldContainExactly
            listOf("by JUDITH VAN WATERKANT", "by LOTTE AHOI", "by CALEESI & KREIS")
        events.filter { it.title == "SONNTAGS IM gART.n" }.map { it.sourceId } shouldContainExactly
            listOf(
                "gartn:2026-08-16-sonntags-im-gart-n",
                "gartn:2026-08-23-sonntags-im-gart-n",
                "gartn:2026-09-06-sonntags-im-gart-n"
            )
    }

    @Test
    fun `splits a b2b slot into both DJs`() {
        event("OUT OF OFFICE").artists.map { it.name }.takeLast(2) shouldContainExactly listOf("Amina", "Luqqi")
        // The right-hand act carries a label affiliation, which stays part of its name.
        event("KOTORI OPEN AIR").artists.map { it.name } shouldContainExactly
            listOf("Sam Shure", "Laserlaura (Silkshake)", "Hemi", "Agily", "Aaron Zeederberg")
    }

    @Test
    fun `trims an inline live marker but keeps the act`() {
        event("OEWERSAUSE").artists.map { it.name } shouldContainExactly
            listOf("ÉLAA", "Kiki Kokolores", "Martha van Straaten", "Saraabb")
    }

    @Test
    fun `reads a sup cast line as the slot's guests, once each`() {
        // The Sunday podcast's hosts sit in a standalone `<sup>mit … und …</sup>` under the slot:
        // a cast line, split at its conjunction (#309). Judith is billed again below and kept once.
        event("SONNTAGS IM gART.n").artists.map { it.name } shouldContainExactly
            listOf(
                "Live Podcast \"Heisse Platten\"",
                "Judith van Waterkant",
                "Ruede Hagelstein",
                "ANNAWAFFEL",
                "Faustina Fauna",
                "Maria die Ruhe"
            )
    }

    @Test
    fun `keeps an act whose own name contains a conjunction whole`() {
        // Lines are billings, so "Caleesi & Kreis" is never split the way a comma-separated
        // lineup would be.
        events.last().artists.map { it.name } shouldContainExactly listOf("Ada", "Caleesi & Kreis", "Gina Sabatini")
    }

    @Test
    fun `stores no artists for an unannounced lineup`() {
        event("AROMA").artists.shouldBeEmpty()
    }

    @Test
    fun `does not mark a night sold out when tickets remain at the door`() {
        val night = event("KALIPO PRESENTS: GARDEN OF SYNTHS")
        night.soldOut shouldBe false
        night.priceNote shouldBe "more tickets at the door"
        night.ticketUrl shouldBe "https://ra.co/events/2375888"
    }

    @Test
    fun `follows the ticket link off the venue's own shop as well as Resident Advisor`() {
        event("SONNTAGS IM gART.n").ticketUrl shouldBe "https://sonntags-judith.ebtix.de/shop"
        event("OEWERSAUSE").ticketUrl shouldBe "https://oewersause.ebtix.de/event/FDC9PZ/shop"
    }

    @Test
    fun `returns no events for a page without a programme`() {
        val empty = Jsoup.parse("<html><body><div class=\"container-component\"></div></body></html>", baseUrl)
        scraper.scrape(empty, baseUrl).shouldBeEmpty()
    }
}

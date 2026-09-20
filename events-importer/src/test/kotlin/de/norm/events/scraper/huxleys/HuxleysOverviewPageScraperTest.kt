package de.norm.events.scraper.huxleys

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [HuxleysOverviewPageScraper].
 *
 * Uses a real `/events` snapshot covering the card variants: a plain show, a sold-out one, a
 * cancelled one, and the four shapes of `.anderungen` change note — a move to another house, a move
 * *into* Huxleys, a new date, and notes that are not status changes at all ("Zusatzshow",
 * "Eintritt ab 18 Jahren!") and must leave the status alone.
 */
class HuxleysOverviewPageScraperTest {
    private val scraper = HuxleysOverviewPageScraper()
    private val baseUrl = "https://huxleysneuewelt.de/events"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/huxleys/huxleys-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(slug: String): ScrapedEvent = events.first { it.sourceId == "huxleys:$slug" }

    @Test
    fun `extracts every event card from the fixture`() {
        events shouldHaveSize 107
    }

    @Test
    fun `assigns each event a unique sourceId`() {
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
    }

    @Test
    fun `parses date, times and lineup for a card`() {
        val thievery = event("2026-08-02-thievery-corporation")
        thievery.title shouldBe "Thievery Corporation"
        thievery.subtitle shouldBe "+ Support: PECES RAROS"
        thievery.eventType shouldBe "CONCERT"
        // The `.date` cell reads "02 Aug." with no year; the slug's ISO prefix supplies it.
        thievery.eventDate shouldBe LocalDate.of(2026, 8, 2)
        thievery.startTime shouldBe LocalTime.of(20, 0)
        thievery.doorsTime shouldBe LocalTime.of(19, 0)
        thievery.sourceUrl shouldBe "https://huxleysneuewelt.de/event/2026-08-02-thievery-corporation"
        thievery.artists shouldContainExactly
            listOf(
                ScrapedArtist("Thievery Corporation", "HEADLINER", titleDerived = true),
                ScrapedArtist("PECES RAROS", "SUPPORT")
            )
    }

    @Test
    fun `flags a sold-out show from its list-item class without changing status`() {
        val thievery = event("2026-08-02-thievery-corporation")
        thievery.soldOut shouldBe true
        thievery.status shouldBe "SCHEDULED"
    }

    @Test
    fun `flags a cancelled show from its list-item class`() {
        val rockLegends = event("2026-11-06-rock-legends")
        rockLegends.title shouldBe "Rock Legends"
        rockLegends.status shouldBe "CANCELLED"
        rockLegends.soldOut shouldBe false
    }

    @Test
    fun `reads a relocation from the change note, which is the only place it is stated`() {
        // "ACHTUNG! Das Konzert wird ins Hole44 verlegt." — no badge, no CSS class.
        event("2026-08-18-current-joys").status shouldBe "RELOCATED"
        // A show moved *into* Huxleys is flagged the same way by the venue; the note travels with
        // the row so the persistence boundary can tell the two ends apart (#1551).
        val arrived = event("2026-10-26-kitty-daisy-lewis")
        arrived.status shouldBe "RELOCATED"
        arrived.statusNote shouldBe "VERLEGT! Die Show wird aus dem Metropol ins Huxleys verlegt."
        arrived.toEventEntity(venueId = 1L, venueSlug = "huxleys-neue-welt", eventSourceId = 1L).status shouldBe "SCHEDULED"
        val left = event("2026-10-29-kate-ryan").toEventEntity(venueId = 1L, venueSlug = "huxleys-neue-welt", eventSourceId = 1L)
        left.status shouldBe "RELOCATED"
        left.relocatedTo shouldBe "Hole44"
    }

    @Test
    fun `reads a new date from the change note as postponed`() {
        // "Das Konzert wurde vom 06.03.2026 auf den 01.10.2026 verschoben!"
        val sampagne = event("2026-10-01-sampagne")
        sampagne.status shouldBe "POSTPONED"
        // The listed date is already the new one.
        sampagne.eventDate shouldBe LocalDate.of(2026, 10, 1)
    }

    @Test
    fun `leaves the status alone for a change note that is not a status change`() {
        event("2026-12-06-artemas").status shouldBe "SCHEDULED"
        event("2026-12-19-orgis-weihnachtsshow").status shouldBe "SCHEDULED"
        event("2026-12-16-azet").status shouldBe "SCHEDULED"
    }

    @Test
    fun `imports the whole listing in chronological order`() {
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
    }
}

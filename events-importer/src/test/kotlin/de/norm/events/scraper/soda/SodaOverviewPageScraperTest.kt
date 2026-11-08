package de.norm.events.scraper.soda

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [SodaOverviewPageScraper].
 *
 * Uses a real `/events` snapshot covering the listing's month groups: the current month,
 * a full recurring month, and two far-future one-offs (Halloween in October, New Year's
 * Eve in December). A fixed [Clock] pins the weekday-based year inference, which is how
 * the year-less calendar block is resolved.
 */
class SodaOverviewPageScraperTest {
    /** Snapshot taken on Thursday 30 July 2026, the listing's first event. */
    private val clock: Clock = Clock.fixed(LocalDate.of(2026, 7, 30).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant(), ZoneId.of("Europe/Berlin"))

    private val scraper = SodaOverviewPageScraper(clock)
    private val baseUrl = "https://www.soda-berlin.de/events"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        events = scraper.scrape(fixtureDocument("soda/soda-overview.html", baseUrl), baseUrl)
    }

    private fun event(sourceIdSuffix: String): ScrapedEvent = events.first { it.sourceId == "soda:$sourceIdSuffix" }

    @Test
    fun `extracts every event snippet from the fixture`() {
        events shouldHaveSize 25
    }

    @Test
    fun `parses title, date, flyer and detail URL for a resident night`() {
        val social = event("soda-social-club-30-07-2026")
        social.title shouldBe "Soda Social Club"
        social.eventType shouldBe "PARTY"
        social.eventDate shouldBe LocalDate.of(2026, 7, 30)
        social.sourceUrl shouldBe "https://www.soda-berlin.de/de/events/soda-social-club-30-07-2026"
        social.imageUrl shouldBe "https://soda.disco2app.com/media/events/819/image/33578"
        // Only "Lounges" is offered on this night — no ticket button, so no ticket URL.
        social.ticketUrl.shouldBeNull()
        // The listing carries no times, prices or prose — the detail page supplies those.
        social.startTime.shouldBeNull()
        social.description.shouldBeNull()
    }

    @Test
    fun `resolves the ticket button to an absolute URL`() {
        event("famous-friday-31-07-2026").ticketUrl shouldBe
            "https://www.soda-berlin.de/de/events/famous-friday-31-07-2026#tickets"
    }

    @Test
    fun `infers the year from the weekday for a date months ahead`() {
        // 31 December 2026 is a Thursday, which is what the calendar block states.
        event("silvester-in-der-kulturbrauerei-31-12-2026").eventDate shouldBe LocalDate.of(2026, 12, 31)
        event("halloween-in-der-kulturbrauerei-samstag-31-10-2026").eventDate shouldBe LocalDate.of(2026, 10, 31)
    }

    @Test
    fun `keys the sourceId on the slug even when the venue spells its date differently`() {
        // Most slugs end in "-DD-MM-YYYY", this one in "-DDMMYY" — the slug is used verbatim
        // and the date comes from the calendar block, so the odd spelling changes nothing.
        val ballermann = event("ballermann-open-air-150826")
        ballermann.title shouldBe "Ballermann Open Air"
        ballermann.eventDate shouldBe LocalDate.of(2026, 8, 15)
    }

    @Test
    fun `types every listing as a party`() {
        events.map { it.eventType }.toSet() shouldBe setOf("PARTY")
        events.all { it.artists.isEmpty() } shouldBe true
    }
}

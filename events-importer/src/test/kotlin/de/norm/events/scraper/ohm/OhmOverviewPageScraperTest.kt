package de.norm.events.scraper.ohm

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [OhmOverviewPageScraper].
 *
 * Parses a static snapshot of OHM Berlin's home page with a **fixed clock**, because the venue's
 * dates carry no year and are resolved relative to today. The year-rollover, missing-time and
 * placeholder-lineup cases the live two-night listing could not show come from the hand-crafted
 * `ohm-overview-edge-cases.html` variant.
 */
class OhmOverviewPageScraperTest {
    private val baseUrl = "https://ohmberlin.com/"

    /** The day the live fixture was captured: the 01/08 night is tonight, the 31/07 one just ended. */
    private val captureClock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneId.of("Europe/Berlin"))

    /** Late December, so a 02/01 date must resolve to the *next* year and a 30/12 one to this year. */
    private val newYearClock: Clock = Clock.fixed(Instant.parse("2026-12-30T12:00:00Z"), ZoneId.of("Europe/Berlin"))

    private fun scrape(
        fixture: String,
        clock: Clock
    ): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/ohm/$fixture")!!
                .bufferedReader()
                .readText()
        return OhmOverviewPageScraper(clock).scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private val events: List<ScrapedEvent> by lazy { scrape("ohm-overview.html", captureClock) }
    private val edgeCases: List<ScrapedEvent> by lazy { scrape("ohm-overview-edge-cases.html", newYearClock) }

    @Test
    fun `discovers every event on the listing`() {
        events shouldHaveSize 2
    }

    @Test
    fun `maps a fully populated event`() {
        val animalia = events.first { it.title == "Animalia" }
        animalia.eventType shouldBe EventType.PARTY.name
        animalia.eventDate shouldBe LocalDate.of(2026, 8, 1)
        animalia.startTime shouldBe LocalTime.of(23, 59)
        animalia.sourceUrl shouldBe baseUrl
        animalia.sourceId shouldBe "ohm:2026-08-01-animalia"
        animalia.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `tags every night Techno, since the club programmes it and the site names no style`() {
        events.map { it.genre }.distinct() shouldBe listOf("Techno")
    }

    @Test
    fun `stores the lineup as DJs and never the title`() {
        val ouch = events.first { it.title == "Ouch x FemmeDecks" }
        ouch.artists.map { it.name } shouldBe listOf("Godsfave", "Kontronatura", "missteikk", "Rafush")
        ouch.artists.map { it.role }.toSet() shouldBe setOf("DJ")
    }

    @Test
    fun `keeps a night that has just ended in the current year`() {
        // 31/07 is the day before the capture date; rolling it forward a year would be wrong.
        val ouch = events.first { it.title == "Ouch x FemmeDecks" }
        ouch.eventDate shouldBe LocalDate.of(2026, 7, 31)
        ouch.startTime shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `publishes no prices or ticket links`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.ticketUrl.shouldBeNull()
            it.soldOut shouldBe false
        }
    }

    @Test
    fun `rolls a January date into the next year when seen from late December`() {
        edgeCases.first { it.title == "New Year Reset" }.eventDate shouldBe LocalDate.of(2027, 1, 2)
    }

    @Test
    fun `keeps a late-December date in the current year`() {
        edgeCases.first { it.title == "Tonight, Time TBC" }.eventDate shouldBe LocalDate.of(2026, 12, 30)
    }

    @Test
    fun `leaves the start time empty when the venue lists none`() {
        edgeCases.first { it.title == "Tonight, Time TBC" }.startTime.shouldBeNull()
    }

    @Test
    fun `drops placeholder and role labels from the lineup`() {
        edgeCases.first { it.title == "Tonight, Time TBC" }.artists.map { it.name } shouldBe listOf("Rroxymore")
    }

    @Test
    fun `skips an item without a title or without a parseable date`() {
        edgeCases.map { it.title } shouldBe listOf("New Year Reset", "Tonight, Time TBC")
    }

    // LA CASITA, 24/09 (#1756): the venue's brackets mark a live act and two credits that are not music.
    @Test
    fun `bills a live act as headliner and drops painting and installation credits`() {
        val html =
            """
            <ul class="event-list"><li class="event-item">
                <div class="event-date"><h2>24/09</h2></div>
                <div class="event-time">22:00</div>
                <h2 class="event-title">LA CASITA</h2>
                <div class="event-lineup medium-type">Banu <br />
            CH3LO <br />
            DJ TOWER<br />
            Huamaniser<br />
            V. (OPERA live)<br />
            SPICY LAB (VCO & THIRTEEN DOZE) <br />
            TEO CLAVERO (Live Painting)<br />
            S.O.N.O.S (Sonic Visual Installation)</div>
            </li></ul>
            """.trimIndent()

        val event = OhmOverviewPageScraper(captureClock).scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        event.artists.map { it.name to it.role } shouldBe
            listOf(
                "Banu" to "DJ",
                "CH3LO" to "DJ",
                "DJ TOWER" to "DJ",
                "Huamaniser" to "DJ",
                "V. (OPERA live)" to "HEADLINER",
                "SPICY LAB (VCO & THIRTEEN DOZE)" to "DJ"
            )
    }

    // The fixtures predate the posters, which the venue added in September 2026 (#1777).
    @Test
    fun `reads the night's poster and leaves the image empty where there is none`() {
        val html =
            """
            <ul class="event-list"><li class="event-item">
                <div class="event-item-details">
                    <div class="event-item-details-more">
                        <div class="event-date"><h2>24/09</h2></div>
                        <div class="event-time">22:00</div>
                        <h2 class="event-title">LA CASITA</h2>
                        <div class="event-lineup medium-type">Banu</div>
                    </div>
                </div>
                <div class="event-poster">
                    <img width="719" height="900" src="https://ohmberlin.com/wp-content/uploads/2026/09/Screenshot-2026-09-07-at-20.02.27-719x900.png" class="attachment-medium size-medium" alt="" />
                </div>
                <div class="event-item-hidden"><div class="event-poster-mobile">
                    <img width="719" height="900" src="https://ohmberlin.com/wp-content/uploads/2026/09/Screenshot-2026-09-07-at-20.02.27-719x900.png" alt="" />
                </div></div>
            </li></ul>
            """.trimIndent()

        val event = OhmOverviewPageScraper(captureClock).scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        event.imageUrl shouldBe "https://ohmberlin.com/wp-content/uploads/2026/09/Screenshot-2026-09-07-at-20.02.27-719x900.png"
        events.forEach { it.imageUrl.shouldBeNull() }
    }

    @Test
    fun `returns an empty list for a page without an event list`() {
        val document = Jsoup.parse("<html><body><section class='events'></section></body></html>", baseUrl)
        OhmOverviewPageScraper(captureClock).scrape(document, baseUrl).shouldBeEmpty()
    }
}

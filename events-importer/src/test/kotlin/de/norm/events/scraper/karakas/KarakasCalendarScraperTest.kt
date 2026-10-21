package de.norm.events.scraper.karakas

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [KarakasCalendarScraper], against a saved copy of the comedy page's published script. */
class KarakasCalendarScraperTest {
    private val scraper = KarakasCalendarScraper()
    private val pageUrl = "https://karakasbar.com/comedy-shows"

    private val script =
        javaClass.classLoader
            .getResourceAsStream("scraper/karakas/karakas-calendar-script.js")!!
            .bufferedReader()
            .readText()

    private fun scrape() = scraper.scrape(script, pageUrl, EventType.COMEDY)!!

    @Test
    fun `scrape reads every calendar entry`() {
        val events = scrape()

        events shouldHaveSize 16
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 16)
        events.last().eventDate shouldBe LocalDate.of(2027, 1, 16)
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = scrape().single { it.eventDate == LocalDate.of(2026, 10, 22) }

        event.title shouldBe "Ric and Ram - Standup Comedy in English"
        event.eventType shouldBe "COMEDY"
        event.startTime shouldBe LocalTime.of(20, 30)
        event.endDate shouldBe LocalDate.of(2026, 10, 22)
        event.endTime shouldBe LocalTime.of(22, 30)
        event.sourceUrl shouldBe pageUrl
        event.sourceId shouldBe "karakas:4878b564-fbbc-4db2-9720-257d39a97b26"
        event.description!! shouldStartWith "A best -selling international double-trouble comedy night"
    }

    @Test
    fun `scrape takes the ticket line out of the description and into the ticket link`() {
        val event = scrape().first { it.title == "Dark Comedy OPEN MIC" }

        event.ticketUrl!! shouldStartWith "https://www.eventbrite.co.uk/e/dark-comedy-open-mic-262"
        event.description!! shouldNotContain "Tickets"
    }

    @Test
    fun `scrape reads a mistyped start time by its first five characters`() {
        val event = scrape().single { it.title == "Katharina Block's Show" }

        event.startTime shouldBe LocalTime.of(20, 30)
        event.endTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `scrape skips a night at another address`() {
        val elsewhere = script.replaceFirst("Karakas, Kurf\\u00FCrstenstra\\u00DFe 9, 10785", "Comedy Club, Grünberger Str. 84")

        scraper.scrape(elsewhere, pageUrl, EventType.COMEDY)!! shouldHaveSize 15
    }

    @Test
    fun `scrape returns null for a script without the calendar widget`() {
        scraper.scrape("window.wsb={};", pageUrl, EventType.COMEDY).shouldBeNull()
    }

    @Test
    fun `scrape returns no events for a calendar without entries`() {
        val empty = """Core.utils.deferBootstrap({componentName:'@widget/CALENDAR/bs-calendar',props:JSON.parse("{\"manualEvents\":[]}")});"""

        scraper.scrape(empty, pageUrl, EventType.COMEDY)!!.shouldBeEmpty()
    }
}

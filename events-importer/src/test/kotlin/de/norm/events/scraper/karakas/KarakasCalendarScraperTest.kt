package de.norm.events.scraper.karakas

import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [KarakasCalendarScraper], against a saved copy of the comedy page's published script. */
class KarakasCalendarScraperTest {
    private val scraper = KarakasCalendarScraper()
    private val pageUrl = "https://karakasbar.com/comedy-shows"

    private val scriptJs = "karakas/karakas-calendar-script.js"

    private val linkedTicketsJs = "karakas/karakas-calendar-script-2026-11.js"

    private fun scrape(script: String = scriptJs) = scraper.scrape(fixtureText(script), pageUrl, EventType.COMEDY)!!

    private fun linkedTicketsEvent(
        date: LocalDate,
        title: String
    ) = scrape(linkedTicketsJs).single { it.eventDate == date && it.title.startsWith(title) }

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
    fun `scrape reads the ticket link from a LINK entity on the word Tickets (#5105)`() {
        val event = linkedTicketsEvent(LocalDate.of(2026, 11, 15), "BAK14 DESI HINGLISH SHOW")

        event.ticketUrl!! shouldStartWith "https://www.eventbrite.de/e/bak14-desi-hinglish-show-tickets-2003895179425"
        event.description.orEmpty() shouldNotContain "Tickets"
    }

    @Test
    fun `scrape reads a LINK entity that starts mid-block on its own line`() {
        val event = linkedTicketsEvent(LocalDate.of(2026, 11, 25), "Sweetspot Comedy")

        event.ticketUrl!! shouldStartWith "https://rausgegangen.de/en/events/sweetspot-comedy-14/"
        event.description!! shouldEndWith "\nDie Gastgeber sind Kawus Kalantar und Sinan Kutscher."
    }

    @Test
    fun `scrape picks the LINK entity on the word Tickets, not the first one in the description`() {
        val event = linkedTicketsEvent(LocalDate.of(2027, 1, 9), "Chaos Kittens")

        event.ticketUrl!! shouldStartWith "https://www.eventbrite.de/e/chaos-kittens-a-feral-open-mic-tickets-2004182258085"
        event.description!! shouldStartWith "Welcome to the open mic"
        event.description shouldNotContain "Tickets"
    }

    @Test
    fun `scrape drops a stray scheme in front of a LINK entity's URL`() {
        val event = linkedTicketsEvent(LocalDate.of(2026, 11, 16), "This Could Get Awkward")

        event.ticketUrl!! shouldStartWith "https://www.eventbrite.de/e/this-could-get-awkward-english-stand-up-comedy"
        event.description.orEmpty() shouldNotContain "Tickets"
    }

    @Test
    fun `scrape reads the ticket line from the second line of a block`() {
        val event = linkedTicketsEvent(LocalDate.of(2026, 11, 22), "La Parranda standup")

        event.ticketUrl!! shouldStartWith "https://www.eventbrite.co.uk/e/la-parranda-standup-en-karakas-tickets-2003529049320"
        event.description!! shouldEndWith "\nLatinos, hispanos, iberoamigos...todos son bienvenidos para echar cotorreo y desmadre en esta noche de comedia!"
        event.description shouldNotContain "Tickets"
    }

    @Test
    fun `scrape still reads a ticket URL written into the text`() {
        val event = linkedTicketsEvent(LocalDate.of(2026, 11, 20), "FRIDAY FUNNIES")

        event.ticketUrl!! shouldStartWith "https://www.eventbrite.co.uk/e/friday-funnies-english-comedy-showcase"
        event.description.orEmpty() shouldNotContain "Tickets"
    }

    @Test
    fun `scrape reads a mistyped start time by its first five characters`() {
        val event = scrape().single { it.title == "Katharina Block's Show" }

        event.startTime shouldBe LocalTime.of(20, 30)
        event.endTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `scrape skips a night at another address`() {
        val elsewhere = fixtureText(scriptJs).replaceFirst("Karakas, Kurf\\u00FCrstenstra\\u00DFe 9, 10785", "Comedy Club, Grünberger Str. 84")

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

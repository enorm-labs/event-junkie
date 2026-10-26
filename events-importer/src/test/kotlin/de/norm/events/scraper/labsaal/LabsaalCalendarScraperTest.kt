package de.norm.events.scraper.labsaal

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [LabsaalCalendarScraper], against a saved copy of the real `/?mec-ical-feed=1` feed. */
class LabsaalCalendarScraperTest {
    private val scraper = LabsaalCalendarScraper()

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(
            javaClass.classLoader
                .getResourceAsStream("scraper/labsaal/labsaal-feed.ics")!!
                .bufferedReader()
                .readText()
        )
    }

    private fun event(slug: String) = events.single { it.sourceId == "labsaal:$slug" }

    @Test
    fun `scrape keeps the programme and drops the groups, courses, workshops and trips`() {
        events shouldHaveSize 22
        events.none { it.title.startsWith("Treffen der") || "Kurs" in it.title } shouldBe true
        events.none { "Workshop" in it.title && "TanzT" !in it.title } shouldBe true
        events.none { "Delitzsch" in it.title } shouldBe true
        events.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 17)
        events.maxOf { it.eventDate } shouldBe LocalDate.of(2027, 2, 14)
    }

    @Test
    fun `scrape maps a night with its prose doors time, ticket link and poster`() {
        val event = event("silvester-im-labsaal-4")

        event.title shouldBe "Silvester im LabSaal"
        event.eventType shouldBe "PARTY"
        event.eventDate shouldBe LocalDate.of(2026, 12, 31)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.endDate.shouldBeNull()
        event.endTime.shouldBeNull()
        event.sourceUrl shouldBe "https://labsaal.de/events/silvester-im-labsaal-4/"
        event.ticketUrl shouldBe "https://labsaal.leoticket.de/50b5d3d6-a14a-4520-ac63-79ba49521bfc"
        event.imageUrl shouldBe "https://labsaal.de/wp-content/uploads/2026/04/Silvester-26.jpeg"
        event.description.shouldNotBeNull() shouldContain "DJ Mayaniko"
        event.description.shouldNotBeNull() shouldNotContain "https://"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `scrape reads the printed wall-clock time, not an offset-shifted one`() {
        val event = event("245")

        event.startTime shouldBe LocalTime.of(17, 0)
        event.eventType shouldBe "SHOW"
    }

    @Test
    fun `scrape keeps a dance night that offers a workshop before it`() {
        val event = event("swinging-tanzt-und-swing-workshop")

        event.eventType shouldBe "PARTY"
        event.doorsTime shouldBe LocalTime.of(18, 30)
        event.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `scrape types each night from the title, then the description, then a music cue`() {
        event("eugene-ionesco-die-nashoerner-labsaal-theater").eventType shouldBe "SHOW"
        event("neuseeland-gods-own-country-das-schoenste-ende-der-welt-315").eventType shouldBe "READING"
        event("prince-party-lasst-uns-die-legende-feiern").eventType shouldBe "PARTY"
        event("112").eventType shouldBe "CONCERT"
        event("648").eventType shouldBe "CONCERT"
        event("luebarser-weihnachtsmarkt-2025").eventType shouldBe "OTHER"
        event("luebarser-weihnachtsmarkt-2025").typeIsFallback shouldBe false
    }

    @Test
    fun `scrape decodes entities and collapses doubled spaces in a title`() {
        event("maire-breatnach-thomas-loefke-celtic-fiddle-harp-and-song").title shouldBe
            "Máire Breatnach & Thomas Loefke – Celtic Fiddle, Harp and Song"
        event("eugene-ionesco-die-nashoerner-labsaal-theater").title shouldBe "Eugène Ionesco: „Die Nashörner“ – LabSaal Theater"
    }

    @Test
    fun `scrape marks a night free when the prose says so`() {
        val event = event("kooperation-mit-der-musikschule-reinickendorf-32")

        event.free shouldBe true
        event.eventType shouldBe "CONCERT"
        event.startTime shouldBe LocalTime.of(19, 0)
        event("silvester-im-labsaal-4").free shouldBe false
    }

    @Test
    fun `isProgramme needs the programme category and no workshop or course before an und`() {
        val programme = listOf("Alle Kalendereinträge", "Alle Veranstaltungen")

        LabsaalCalendarScraper.isProgramme("Swinging TanzT und Swing-Workshop", programme) shouldBe true
        LabsaalCalendarScraper.isProgramme("VOLKSTANZ Workshop mit ANTOINE SENGERS", programme) shouldBe false
        LabsaalCalendarScraper.isProgramme("Tanzkurs im LabSaal", programme) shouldBe false
        LabsaalCalendarScraper.isProgramme("QIGONG Kurs 1", programme) shouldBe false
        LabsaalCalendarScraper.isProgramme("Popsong Singers", listOf("Alle Gruppen Termine", "Popsong Singers")) shouldBe false
    }

    @Test
    fun `scrape types a Lesebühne's year review a reading from its description`() {
        val calendar =
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Europe/Berlin:20270109T200000\r\n" +
                "SUMMARY:BRAUSEBOYS: AUF NIMMERWIEDERSEHEN 2026\r\nURL:https://labsaal.de/events/brauseboys/\r\n" +
                "DESCRIPTION:Seit zwei Jahrzehnten blicken die Brauseboys auf das Jahr zurück\\, das sie in ihren " +
                "wöchentlichen Leseshows kommentiert haben.\r\nCATEGORIES:Alle Veranstaltungen\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

        scraper.scrape(calendar).single().eventType shouldBe "READING"
    }

    @Test
    fun `scrape skips an entry without a URL and returns nothing for an empty calendar`() {
        val calendar =
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=Europe/Berlin:20261101T190000\r\nSUMMARY:Ohne Link\r\n" +
                "CATEGORIES:Alle Veranstaltungen\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

        scraper.scrape(calendar).shouldBeEmpty()
        scraper.scrape("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n").shouldBeEmpty()
    }
}

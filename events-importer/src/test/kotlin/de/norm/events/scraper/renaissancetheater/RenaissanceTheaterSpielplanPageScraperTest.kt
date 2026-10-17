package de.norm.events.scraper.renaissancetheater

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class RenaissanceTheaterSpielplanPageScraperTest {
    private val baseUrl = "https://renaissance-theater.de/spielplan/"
    private val clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), BERLIN)
    private val scraper = RenaissanceTheaterSpielplanPageScraper(clock)

    private val events by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/renaissancetheater/renaissancetheater-spielplan.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String) = events.single { it.sourceId == sourceId }

    @Test
    fun `parses every performance but the house tours`() {
        events shouldHaveSize 162
        events.map { it.title } shouldNotContain "Führung durch das Renaissance-Theater"
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
    }

    @Test
    fun `parses a play with its performance note`() {
        val play = event("renaissance_theater:von-fischen-und-frauen-2026-10-16-1930")

        play.title shouldBe "VON FISCHEN UND FRAUEN"
        play.subtitle shouldBe "Kleines, extrem harmloses Drama übers Angeln · Anschließend Publikumsgespräch im Bruckner-Foyer"
        play.eventType shouldBe EventType.SHOW.name
        play.typeIsFallback shouldBe false
        play.eventDate shouldBe LocalDate.of(2026, 10, 16)
        play.startTime shouldBe LocalTime.of(19, 30)
        play.sourceUrl shouldBe "https://renaissance-theater.de/produktion/von-fischen-und-frauen/"
        play.ticketUrl shouldBe "https://indiv.themisweb.fr/0426/fEventChoiceIsMade.aspx?idstructure=0426&idevent=1343"
        play.room.shouldBeNull()
        play.artists.shouldBeEmpty()
    }

    @Test
    fun `takes the year from the heading, or from the weekday where the heading prints none`() {
        event("renaissance_theater:ferdinand-von-schirach-der-stille-freund-2027-09-18-2000").eventDate shouldBe LocalDate.of(2027, 9, 18)
        events.minOf { it.eventDate } shouldBe LocalDate.of(2026, 10, 16)
        events.filter { it.eventDate.year == 2026 }.maxOf { it.eventDate.monthValue } shouldBe 12
    }

    @Test
    fun `types a reading in the foyer and bills its reader`() {
        val reading = event("renaissance_theater:christian-brueckner-liest-truman-capote-erzaehlung-2026-10-18-1130")

        reading.eventType shouldBe EventType.READING.name
        reading.room shouldBe "Bruckner-Foyer"
        reading.artists shouldContainExactly listOf(ScrapedArtist("Christian Brückner", titleDerived = true))
    }

    @Test
    fun `types a concert and bills the names after its closing mit`() {
        val concert = events.first { it.title == "Drei Weihnachtsmänner singen" }

        concert.eventType shouldBe EventType.CONCERT.name
        concert.artists.map { it.name } shouldContainExactly listOf("Dieter Landuris", "Axel Herrig", "Carsten Gerlitz")
    }

    @Test
    fun `bills nobody when the words after mit are not names`() {
        events.first { it.title == "Lächelnd durch den Ernst der Zeit" }.artists.map { it.name } shouldContainExactly listOf("Max Hopp")
        events.first { it.title == "LIEBE IN ZEITEN DES HASSES" }.artists.shouldBeEmpty()
        events.first { it.title == "NEIL YOUNG – COMES A SONG" }.artists.shouldBeEmpty()
    }

    @Test
    fun `types every performance of a Kabarett run as comedy, and a slam as a reading`() {
        // Only the first of the three performances carries the `Theaterkabarett` badge.
        events.filter { it.title == "BERLINER WERDEN IN 60 MINUTEN ..." }.map { it.eventType } shouldContainExactly List(3) { EventType.COMEDY.name }
        events.first { it.title == "BEST OF TRUE CRIME SLAM" }.eventType shouldBe EventType.READING.name
    }

    @Test
    fun `types a night that names no format a play, not a fallback`() {
        val play = events.first { it.title == "HERR TEUFEL FAUST - NACH JOHANN WOLFGANG VON GOETHE FAUST I" }

        play.eventType shouldBe EventType.SHOW.name
        play.typeIsFallback shouldBe false
        play.artists.map { it.name } shouldContainExactly listOf("Dominique Horwitz")
    }

    @Test
    fun `reads a cancellation from the performance note`() {
        val html =
            """
            <main>
              <div class="rt-headline"><div class="rt-fullbar"><h1>November</h1></div></div>
              <div class="grid px-5"><div class="rt-spielplan-day flex"><div class="rt-sp-day">20</div><div class="rt-sp-wday">Fr</div>
                <div class="rt-sp-times"><div class="rt-sp-date flex"><div class="rt-sp-time">19.30</div><div class="rt-sp-teaser">
                  <a href="https://renaissance-theater.de/produktion/marlene/"><h4>Marlene<span>Entfällt</span></h4></a>
                </div></div></div></div></div>
              <div class="grid px-5"><div class="rt-spielplan-day flex"><div class="rt-sp-day">21</div><div class="rt-sp-wday">Sa</div>
                <div class="rt-sp-times"><div class="rt-sp-date flex"><div class="rt-sp-time">15.00</div><div class="rt-sp-teaser">
                  <a href="https://renaissance-theater.de/produktion/pippi/"><h4>Pippi Langstrumpf</h4></a><p>Ein Theaterstück für Kinder ab 5 Jahren</p>
                </div></div></div></div></div>
            </main>
            """.trimIndent()

        val parsed = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

        parsed.map { it.title } shouldContainExactly listOf("Marlene")
        parsed.single().status shouldBe "CANCELLED"
        parsed.single().eventDate shouldBe LocalDate.of(2026, 11, 20)
    }
}

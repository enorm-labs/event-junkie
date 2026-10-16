package de.norm.events.scraper.zebranotheater

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class ZebranoTheaterProgrammPageScraperTest {
    private val baseUrl = "https://www.zebrano-theater.de/programm.html"
    private val scraper = ZebranoTheaterProgrammPageScraper()

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/zebranotheater/zebranotheater-programm.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(date: String): ScrapedEvent = events.single { it.eventDate == LocalDate.parse(date) }

    private fun scrapeTable(
        heading: String,
        rows: String
    ): List<ScrapedEvent> = scraper.scrape(Jsoup.parse("<h2>$heading</h2><table><tbody>$rows</tbody></table>", baseUrl), baseUrl)

    private fun row(
        date: String,
        time: String,
        billing: String
    ): String = "<tr><td>Fr.</td><td>$date</td><td>$time</td><td>$billing</td><td>15*/20 &euro;</td><td>Musik</td></tr>"

    @Test
    fun `reads every evening of every month table`() {
        events shouldHaveSize 36
        events.map { it.eventDate }.distinct() shouldHaveSize 36
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 2)
        events.last().eventDate shouldBe LocalDate.of(2027, 12, 10)
    }

    @Test
    fun `reads a fully populated evening`() {
        val concert = event("2026-11-25")
        concert.title shouldBe "Beppe Gambetta im Konzert: Terra Madre"
        concert.subtitle shouldBe "Spezialgast: Felix Meyer"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.typeIsFallback shouldBe false
        concert.startTime shouldBe LocalTime.of(19, 30)
        concert.sourceUrl shouldBe "https://www.zebrano-theater.de/programm/20261125.shtml"
        concert.sourceId shouldBe "zebrano_theater:20261125"
        concert.ticketUrl shouldBe "https://www.eventim-light.com/de/a/65c568fdcadf314c3518ca9a/e/6aacfcb7333a943929559b06"
        concert.priceBoxOffice shouldBe BigDecimal("20")
        concert.priceNote shouldBe "ermäßigt 15 €"
        concert.artists shouldContainExactly listOf(ScrapedArtist(name = "Beppe Gambetta"), ScrapedArtist(name = "Felix Meyer", role = "SUPPORT"))
    }

    @Test
    fun `maps the house category to an event type`() {
        event("2026-10-02").eventType shouldBe EventType.SHOW.name
        event("2026-10-09").eventType shouldBe EventType.READING.name
        event("2026-10-13").eventType shouldBe EventType.READING.name
        event("2026-11-14").eventType shouldBe EventType.READING.name
        event("2026-12-19").eventType shouldBe EventType.SHOW.name
        event("2027-03-05").eventType shouldBe EventType.COMEDY.name
    }

    @Test
    fun `bills the host of his own show and the evening's applicant as a guest`() {
        event("2026-10-04").artists shouldContainExactly
            listOf(ScrapedArtist(name = "Sebastian Krämer"), ScrapedArtist(name = "Emil Bach", role = "SUPPORT"))
        event("2026-12-06").artists shouldContainExactly
            listOf(ScrapedArtist(name = "Sebastian Krämer"), ScrapedArtist(name = "Ulan & Bator", role = "SUPPORT"))
    }

    @Test
    fun `bills a reading stage's cast from its mit line`() {
        val reading = event("2026-12-08")
        reading.title shouldBe "Die Dienstagspropheten"
        reading.subtitle shouldBe "mit Sebastian Krämer, Martin Betz, Georg Weisfeld und Bernhard Lassahn"
        reading.artists.map { it.name } shouldContainExactly listOf("Sebastian Krämer", "Martin Betz", "Georg Weisfeld", "Bernhard Lassahn")
        reading.priceBoxOffice shouldBe BigDecimal("10")
        reading.priceNote.shouldBeNull()
    }

    @Test
    fun `bills no one for a play or a series head`() {
        event("2026-12-19").artists.shouldBeEmpty()
        event("2026-11-14").artists.shouldBeEmpty()
    }

    @Test
    fun `splits a duo and a featured guest`() {
        event("2026-11-20").artists.map { it.name } shouldContainExactly listOf("Ulrich Zehfuß", "Claudia Fink")
        event("2027-01-29").artists shouldContainExactly
            listOf(ScrapedArtist(name = "George Leitenberger"), ScrapedArtist(name = "Andreas Albrecht", role = "SUPPORT"))
    }

    @Test
    fun `keeps an evening announced before its page on the listing`() {
        val announced = event("2027-01-17")
        announced.title shouldBe "Johannes Kirchberg: Im Leuchtturm durch die Zeit"
        announced.subtitle.shouldBeNull()
        announced.startTime shouldBe LocalTime.of(18, 0)
        announced.sourceUrl shouldBe baseUrl
        announced.ticketUrl.shouldBeNull()
        announced.artists.map { it.name } shouldContainExactly listOf("Johannes Kirchberg")
    }

    @Test
    fun `reads a row whose billing cell is left unclosed`() {
        event("2026-12-06").subtitle shouldBe "Die Kultshow mit dem Elefanten-Orakel\n(Antragsteller: Ulan & Bator)"
        event("2026-12-08").eventType shouldBe EventType.READING.name
    }

    @Test
    fun `takes the year from the heading text, not its id`() {
        event("2027-10-03").title shouldBe "Sebastian Krämers Club Genie und Wahnsinn"
    }

    @Test
    fun `gives two shows on one date ids of their own`() {
        val shows =
            scrapeTable(
                "Mai 2027",
                row("07.05.", "16:00 Uhr", "Anna Beispiel: Matinee") + row("07.05.", "20:00 Uhr", "Anna Beispiel: Soiree")
            )
        shows.map { it.sourceId } shouldContainExactly listOf("zebrano_theater:20270507-1600", "zebrano_theater:20270507-2000")
    }

    @Test
    fun `dates a January row under a December heading in the next year`() {
        scrapeTable("Dezember 2026", row("02.01.", "19:30 Uhr", "Anna Beispiel: Neujahr")).single().eventDate shouldBe LocalDate.of(2027, 1, 2)
    }

    @Test
    fun `returns nothing for a page without month tables`() {
        scraper.scrape(Jsoup.parse("<h2>Sommerpause</h2>", baseUrl), baseUrl).shouldBeEmpty()
    }
}

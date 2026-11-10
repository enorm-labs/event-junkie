package de.norm.events.scraper.scheinbar

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class ScheinbarProgrammPageScraperTest {
    private val scraper = ScheinbarProgrammPageScraper()
    private val url = "https://www.scheinbar.de/programm/"

    private val events by lazy { scraper.scrape(fixtureDocument("scheinbar/scheinbar-programm.html", url), url) }

    @Test
    fun `reads every evening of the three months`() {
        events shouldHaveSize 50
        events.last().eventDate.monthValue shouldBe 12
    }

    @Test
    fun `reads an Open Stage night and bills its host`() {
        val night = events.first { it.eventDate == LocalDate.of(2026, 10, 7) }

        night.title shouldBe "Open Stage präsentiert von Hans und Goldi"
        night.subtitle shouldBe null
        night.eventType shouldBe "SHOW"
        night.startTime shouldBe LocalTime.of(20, 0)
        night.priceBoxOffice shouldBe BigDecimal("12.00")
        night.sourceUrl shouldBe "https://www.scheinbar.de/programm/hans-und-goldi/"
        night.sourceId shouldBe "scheinbar:2026-10-07"
        night.ticketUrl shouldBe "https://tickets.scheinbar.de/shows/2026-10-07"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Hans und Goldi"))
    }

    @Test
    fun `reads a guest show with its programme and performers`() {
        val show = events.first { it.eventDate == LocalDate.of(2026, 10, 4) }

        show.title shouldBe "Gisa Bergmann & Horst Blue"
        show.subtitle shouldBe "Hoffentlich hält sich die Bräune"
        show.artists.map { it.name } shouldContainExactly listOf("Gisa Bergmann", "Horst Blue")
    }

    @Test
    fun `bills the presenter of a show whose first line ends in praesentiert and a colon`() {
        // Event 13351 (#4833): the detail page names no other act.
        val html =
            """
            <h2 class="month">März 2027</h2><ul class="programm_uebersicht"><li><a href="/programm/der-dicke-dirk/">
            <div class="date_time"><div class="date">07</div><div class="time">20:00</div></div>
            <div class="prog_desc"><span class="h3 rot">Der dicke Dirk präsentiert:</span> <span class="h2 gruen">Das Programm</span></div>
            <div class="preis">16,00 &euro;</div></a></li></ul>
            """.trimIndent()

        val show = scraper.scrape(Jsoup.parse(html, url), url).single()

        show.title shouldBe "Der dicke Dirk präsentiert:"
        show.subtitle shouldBe "Das Programm"
        show.artists shouldContainExactly listOf(ScrapedArtist(name = "Der dicke Dirk"))
    }

    @Test
    fun `marks a sold-out evening and keeps its price`() {
        val soldOut = events.first { it.eventDate == LocalDate.of(2026, 10, 3) }

        soldOut.soldOut shouldBe true
        soldOut.ticketUrl shouldBe null
        soldOut.priceBoxOffice shouldBe BigDecimal("14.00")
    }

    @Test
    fun `keeps a full and a reduced price as the note`() {
        val show = events.first { it.priceNote != null }

        show.priceBoxOffice shouldBe BigDecimal("18.00")
        show.priceNote shouldBe "18,00 / 15,00 €"
    }
}

package de.norm.events.scraper.scheinbar

import de.norm.events.scraper.ScrapedArtist
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

    private val events by lazy {
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/scheinbar/scheinbar-programm.html")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )
    }

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

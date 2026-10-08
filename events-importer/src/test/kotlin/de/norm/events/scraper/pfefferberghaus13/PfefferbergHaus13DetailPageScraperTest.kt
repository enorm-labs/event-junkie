package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedField
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class PfefferbergHaus13DetailPageScraperTest {
    private val scraper = PfefferbergHaus13DetailPageScraper()

    private fun page(
        fixture: String,
        slug: String
    ) = "https://haus13.pfefferwerk.de/event/$slug/".let { url ->
        scraper.scrape(Jsoup.parse(read("pfefferberghaus13-event-$fixture.html"), url), url)!!
    }

    @Test
    fun `reads the date, doors, presale price and the listed lineup`() {
        val event = page("punk-rocktopus", "punk-rocktopus-vol-3")

        event.eventDate shouldBe LocalDate.of(2026, 11, 7)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime.shouldBeNull()
        event.pricePresale shouldBe BigDecimal("17")
        event.priceBoxOffice.shouldBeNull()
        event.priceNote shouldBe "ab 17€"
        event.ticketUrl shouldStartWith "https://www.eventim-light.com/"
        event.imageUrl shouldBe "https://haus13.pfefferwerk.de/wp-content/uploads/2026/09/punkrocktopus-neu.jpg"
        event.artists.map { it.name } shouldBe listOf("Limbo Boys", "Hartholz", "Unglaublicher Vorfall", "AOP")
        event.description!! shouldStartWith "Punk Rocktopus Vol. 3 mit:\nLimbo Boys"
        event.detailPageOwns shouldBe setOf(ScrapedField.DESCRIPTION, ScrapedField.IMAGE, ScrapedField.START_TIME)
    }

    @Test
    fun `reads a box-office range at its lower bound`() {
        val event = page("resonanzen", "resonanzen-internationale-klaenge-rock-den-berg")

        event.startTime shouldBe LocalTime.of(19, 30)
        event.priceBoxOffice shouldBe BigDecimal("7")
        event.pricePresale.shouldBeNull()
        event.priceNote shouldBe "7-10€"
        event.ticketUrl.shouldBeNull()
    }

    @Test
    fun `reads a free debate as other`() {
        val event = page("europa-kontrovers", "europa-kontrovers-frieden-sichern-was-zivile-resilienz-in-berlin-und-europa-leisten-kann")

        event.eventType shouldBe EventType.OTHER.name
        event.free shouldBe true
        event.doorsTime shouldBe LocalTime.of(17, 30)
        event.startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `yields null for a page without the event block`() {
        scraper
            .scrape(
                Jsoup.parse("<html><body></body></html>", "https://haus13.pfefferwerk.de/event/x/"),
                "https://haus13.pfefferwerk.de/event/x/"
            ).shouldBeNull()
    }

    private fun read(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/pfefferberghaus13/$name")!!
            .bufferedReader()
            .readText()
}

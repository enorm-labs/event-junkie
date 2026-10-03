package de.norm.events.scraper.ufafabrik

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class UfaFabrikMonthPageScraperTest {
    private val baseUrl = "https://ufafabrik.de/spielplan.html"

    private fun month(name: String) =
        UfaFabrikMonthPageScraper().scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/ufafabrik/$name")!!
                    .bufferedReader()
                    .readText(),
                baseUrl
            )
        )

    private val october = month("ufafabrik-spielplan.html")

    @Test
    fun `reads every show of the month but the children's shows`() {
        october shouldHaveSize 30
        month("ufafabrik-program-202611.html") shouldHaveSize 29
        october.map { it.title } shouldNotContain "TurTurTheater"
        october.map { it.title } shouldNotContain "Figurentheater Ute Kahmann"
        october.map { it.title } shouldNotContain "Verheldet"
        october.map { it.title } shouldNotContain "Volle Lotte"
    }

    @Test
    fun `parses a concert row in full`() {
        val event = october.single { it.sourceId == "ufa_fabrik:40154-2026-10-01-2000" }
        event.title shouldBe "ELSA"
        event.eventType shouldBe EventType.CONCERT.name
        event.genre shouldBe "Konzert"
        event.eventDate shouldBe LocalDate.of(2026, 10, 1)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.room shouldBe "Varieté Salon"
        event.sourceUrl shouldBe "https://ufafabrik.de/veranstaltung/40154/elsa"
        event.ticketUrl shouldBe "https://ufafabrik.de/node/40154/booking"
        event.pricePresale shouldBe BigDecimal("20.00")
        event.priceBoxOffice shouldBe BigDecimal("20.00")
        event.artists.map { it.name } shouldBe listOf("ELSA")
        event.imageUrl.orEmpty().startsWith("https://ufafabrik.de/sites/default/files/") shouldBe true
    }

    @Test
    fun `a run keeps one row per date and time`() {
        october.filter { it.sourceUrl.endsWith("/39664/kamisi-die-80er-jahre-comedy-show") }.map { it.sourceId } shouldBe
            listOf("ufa_fabrik:39664-2026-10-09-2000", "ufa_fabrik:39664-2026-10-10-2000")
    }

    @Test
    fun `reads presale and box office where the house labels them, and the banners`() {
        val krumbiegel = october.single { it.title == "Sebastian Krumbiegel" }
        krumbiegel.pricePresale shouldBe BigDecimal("22.00")
        krumbiegel.priceBoxOffice shouldBe BigDecimal("27.00")
        krumbiegel.status shouldBe EventStatus.CANCELLED.name
        krumbiegel.ticketUrl.shouldBeNull()
        october.single { it.title == "Ahmet Bozkuş" }.pricePresale.shouldBeNull()
    }

    @Test
    fun `types comedy and Kabarett first, then stage formats before music`() {
        genreType("Musikkabarett") shouldBe EventType.COMEDY.name
        genreType("Kabarett") shouldBe EventType.COMEDY.name
        genreType("Comedy/Puppenspiel") shouldBe EventType.COMEDY.name
        genreType("Comedy-Theater") shouldBe EventType.COMEDY.name
        genreType("Türkische Comedy") shouldBe EventType.COMEDY.name
        genreType("Autorinnen Lesung") shouldBe EventType.READING.name
        genreType("Jazz & Poetry") shouldBe EventType.READING.name
        genreType("Filmpremiere & Crossover-Performance") shouldBe EventType.SCREENING.name
        genreType("Singer Songwriter") shouldBe EventType.CONCERT.name
        genreType("Konzert & Tanz") shouldBe EventType.CONCERT.name
        genreType("Infoabend") shouldBe EventType.OTHER.name
        genreType(null) shouldBe EventType.OTHER.name
    }

    @Test
    fun `a children's show is priced per child, Kita child or teacher`() {
        isChildrensShow(listOf("Kinder", "Erwachsene")) shouldBe true
        isChildrensShow(listOf("Eintritt", "Erzieher*innen:")) shouldBe true
        isChildrensShow(listOf("Eintritt", "Ermäßigt", "Studenten (nur im VVK)")) shouldBe false
    }

    @Test
    fun `an empty month yields nothing`() {
        UfaFabrikMonthPageScraper().scrape(Jsoup.parse("<table></table>", baseUrl)).shouldBeEmpty()
    }
}

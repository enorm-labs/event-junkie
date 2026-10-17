package de.norm.events.scraper.claerchens

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [ClaerchensOverviewPageScraper], against a saved copy of the real programme page. */
class ClaerchensOverviewPageScraperTest {
    private val scraper = ClaerchensOverviewPageScraper()
    private val baseUrl = "https://claerchensball.haus/programm/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/claerchens/claerchens-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(
        title: String,
        date: String
    ) = events.single { it.title == title && it.eventDate == LocalDate.parse(date) }

    @Test
    fun `scrape reads the evening programme of the whole calendar once`() {
        events shouldHaveSize EXPECTED_EVENTS
        events.map { it.sourceId }.distinct() shouldHaveSize EXPECTED_EVENTS
    }

    @Test
    fun `scrape reads a Sunday concert in full`() {
        val concert = event("Sonntagskonzert im Spiegelsaal", "2026-10-18")

        concert.sourceId shouldBe "claerchens_ballhaus:sonntagskonzert-im-spiegelsaal-7-2026-10-18-1700"
        concert.sourceUrl shouldBe "https://claerchensball.haus/sonntagskonzert-im-spiegelsaal-7/"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.startTime shouldBe LocalTime.of(17, 0)
        concert.doorsTime shouldBe LocalTime.of(16, 30)
        concert.description shouldBe "Einlass: 16:30 Uhr"
        concert.endTime.shouldBeNull()
        concert.artists.shouldBeEmpty()
    }

    @Test
    fun `a weekly dance night is a party on each of its dates`() {
        val salsa = events.filter { it.title == "Salsa" && it.eventDate >= LocalDate.parse("2026-10-17") }

        salsa shouldHaveSize 9
        salsa.map { it.sourceId }.distinct() shouldHaveSize 9
        salsa.first().sourceId shouldBe "claerchens_ballhaus:salsa-2026-10-19-1900"
        salsa.forEach { it.eventType shouldBe EventType.PARTY.name }
        salsa.first().description.shouldNotBeNull() shouldContain "Tanzabend: 20:15 - 0:00"
        salsa.first().imageUrl.shouldNotBeNull()
    }

    @Test
    fun `the practice evening with a DJ is a party with its door price`() {
        val evening = event("Standard Latein", "2026-10-22")

        evening.eventType shouldBe EventType.PARTY.name
        evening.priceBoxOffice shouldBe BigDecimal("10")
    }

    @Test
    fun `courses, workshops, the house tour, the choir, the dinner and children's concerts are dropped`() {
        val titles = events.map { it.title }

        titles shouldNotContain "Swing Workshop im Spiegelsaal"
        titles shouldNotContain "Vortrag über Clärchens Geschichte im Spiegelsaal"
        titles shouldNotContain "YesChoir!"
        titles shouldNotContain "Weinmahleins"
        titles shouldNotContain "Kinderkonzert im Spiegelsaal"
        titles shouldNotContain "Familienkonzert im Spiegelsaal"
    }

    @Test
    fun `titles are decoded and the slams are typed`() {
        event("Kiezpoeten – Clärchens Crime Slam", "2026-11-01").eventType shouldBe EventType.READING.name
        event("Kiezpoeten – Clärchens Comedy", "2027-01-17").eventType shouldBe EventType.COMEDY.name
        event("DSO x Clärchens Ballhaus", "2026-11-01").eventType shouldBe EventType.CONCERT.name
        event("80er/90er Party im Spiegelsaal", "2026-10-30").eventType shouldBe EventType.PARTY.name
    }

    @Test
    fun `an act billed before Konzert is the headliner`() {
        event("Donata – Konzert", "2026-11-12").artists.map { it.name } shouldContainExactly listOf("Donata")
    }

    @Test
    fun `a night that opens with a course stays and a course alone goes`() {
        isProgramme("Tango", "tango", "18:30 Uhr - Anfängerkurs\n20:30 Uhr - Milonga").shouldBeTrue()
        isProgramme("Salsa Kurs", "salsa-kurs", "Tanzkurs: 19:00 - 20:15").shouldBeFalse()
        isProgramme("Vortrag", "tour-durchs-haus", null).shouldBeFalse()
        isProgramme("Sonntagskonzert im Spiegelsaal", "sonntagskonzert", "Einlass: 16:30 Uhr").shouldBeTrue()
    }

    @Test
    fun `a page without the calendar yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private companion object {
        const val EXPECTED_EVENTS = 91
    }
}

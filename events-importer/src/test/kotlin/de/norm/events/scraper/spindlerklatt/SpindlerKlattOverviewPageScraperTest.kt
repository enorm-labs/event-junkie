package de.norm.events.scraper.spindlerklatt

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for [SpindlerKlattOverviewPageScraper], against a saved copy of the real club page.
 * The page prints no year, so the clock is pinned to the day the copy was saved.
 */
class SpindlerKlattOverviewPageScraperTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val scraper = SpindlerKlattOverviewPageScraper(Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), berlin))
    private val baseUrl = "https://www.spindlerklatt.com/club"

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(fixtureDocument("spindlerklatt/spindlerklatt-overview.html", baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun scrapeCards(
        vararg cards: String,
        scraper: SpindlerKlattOverviewPageScraper = this.scraper
    ) = scraper.scrape(Jsoup.parse(cards.joinToString(""), baseUrl), baseUrl)

    private fun card(
        meta: String,
        title: String,
        href: String? = null
    ): String {
        val body = "<span class=\"meta\">$meta</span><h3 class=\"h4\">$title</h3>"
        return if (href == null) "<div class=\"card\">$body</div>" else "<a class=\"card\" href=\"$href\">$body</a>"
    }

    @Test
    fun `scrape reads every night of the programme`() {
        events shouldHaveSize 6
        events.map { it.eventDate.toString() } shouldContainExactly
            listOf("2026-11-14", "2026-11-20", "2026-11-21", "2026-12-05", "2026-12-12", "2026-12-31")
        events.map { it.title } shouldContainExactly
            listOf(
                "Ü30 Party",
                "Ü60 Party",
                "IParty People Disney Party",
                "Old School Vibes",
                "BLN REUNION – Indoorfestival",
                "New Years Eve – Discothèque 2026 / 2027"
            )
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = night("2026-11-14")

        event.title shouldBe "Ü30 Party"
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.endTime.shouldBeNull()
        event.endDate.shouldBeNull()
        event.doorsTime.shouldBeNull()
        event.imageUrl shouldBe "https://static.wixstatic.com/media/3f2613_81cd91bdb67a4824b83ec9ef713fcdf8~mv2.jpg"
        event.ticketUrl shouldBe "http://www.ue30party-berlin.de/"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "spindler_klatt:2026-11-14"
    }

    @Test
    fun `every linked card keeps its ticket link`() {
        night("2026-11-21").ticketUrl shouldBe
            "https://www.eventim.de/artist/iparty-people/ipartypeople-berlin-disney-party-3354226/?affiliate=GMD"
        night("2026-12-31").ticketUrl shouldBe "https://www.newyearseve.berlin/"
        night("2026-12-31").startTime shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `a card without a link has no ticket link and keeps its flyer`() {
        val event = night("2026-11-20")

        event.ticketUrl.shouldBeNull()
        event.imageUrl shouldBe "https://static.wixstatic.com/media/3f2613_6bd89124a6a04127890f812b6eb752e2~mv2.png"
    }

    @Test
    fun `a range in hours sets the start and the end on the same day`() {
        val event = night("2026-11-20")

        event.startTime shouldBe LocalTime.of(19, 0)
        event.endTime shouldBe LocalTime.of(23, 0)
        event.endDate shouldBe LocalDate.of(2026, 11, 20)
    }

    @Test
    fun `an end time past midnight rolls to the next day`() {
        val event = scrapeCards(card("Sa · 14.11. · 23:00 – 05:00 Uhr", "Late Night")).single()

        event.startTime shouldBe LocalTime.of(23, 0)
        event.endTime shouldBe LocalTime.of(5, 0)
        event.endDate shouldBe LocalDate.of(2026, 11, 15)
    }

    @Test
    fun `a night in January after New Year's Eve falls in the next year`() {
        val december = SpindlerKlattOverviewPageScraper(Clock.fixed(Instant.parse("2026-12-20T12:00:00Z"), berlin))

        val events =
            scrapeCards(
                card("Do · 31.12. · 21:00 Uhr", "New Years Eve"),
                card("Sa · 09.01. · 22:00 Uhr", "Ü30 Party"),
                scraper = december
            )

        events.map { it.eventDate } shouldContainExactly listOf(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 9))
        events.map { it.sourceId } shouldContainExactly listOf("spindler_klatt:2026-12-31", "spindler_klatt:2027-01-09")
    }

    @Test
    fun `a long title broken across lines is joined into one line`() {
        val long = "Die lange Nacht der 80er, 90er und 2000er – mit Hits von Madonna bis Britney und Gästen aus ganz Berlin"

        val event = scrapeCards(card("Sa · 14.11. · 22:00 Uhr", long.replace(" – ", "<br> – "))).single()

        event.title shouldBe long
    }

    @Test
    fun `a card without a date or a title is skipped and the others are kept`() {
        val events =
            scrapeCards(
                card("Demnächst", "Coming soon"),
                card("Sa · 21.11. · 22:00 Uhr", ""),
                card("Sa · 14.11. · 22:00 Uhr", "Ü30 Party")
            )

        events.map { it.title } shouldContainExactly listOf("Ü30 Party")
    }

    @Test
    fun `a card without a time keeps the night without a start`() {
        val event = scrapeCards(card("Sa · 14.11.", "Ü30 Party")).single()

        event.eventDate shouldBe LocalDate.of(2026, 11, 14)
        event.startTime.shouldBeNull()
        event.imageUrl.shouldBeNull()
    }

    @Test
    fun `a page without a card yields no events`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Club</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}

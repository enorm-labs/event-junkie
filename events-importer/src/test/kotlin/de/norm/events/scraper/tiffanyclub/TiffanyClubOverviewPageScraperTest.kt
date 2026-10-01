package de.norm.events.scraper.tiffanyclub

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
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
import java.time.ZoneId

/** Unit tests for [TiffanyClubOverviewPageScraper], against a snapshot taken on 2026-10-01. */
class TiffanyClubOverviewPageScraperTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val sourceUrl = "https://tiffany-berlin.de/upcoming-events/"
    private val events = TiffanyClubOverviewPageScraper(clock).scrape(Jsoup.parse(fixture(), sourceUrl), sourceUrl)

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/tiffanyclub/tiffanyclub-overview.html")!!
            .bufferedReader()
            .readText()

    private fun on(
        date: LocalDate,
        title: String
    ): ScrapedEvent = events.first { it.eventDate == date && it.title == title }

    @Test
    fun `parses every public night of the listing`() {
        // 36 items: two private bookings and two speed-dating evenings are left out.
        events shouldHaveSize 32
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 1)
        events.last().eventDate shouldBe LocalDate.of(2027, 1, 29)
    }

    @Test
    fun `parses a fully populated night`() {
        val night = on(LocalDate.of(2026, 10, 3), "Latin Hell")

        night.subtitle shouldBe "Para la Cultura"
        night.eventType shouldBe EventType.PARTY.name
        night.startTime shouldBe LocalTime.of(23, 0)
        night.sourceUrl shouldBe "https://tiffany-berlin.de/event/03-10-2026-latin-hell-para-la-cultura/"
        night.sourceId shouldBe "tiffany_club:03-10-2026-latin-hell-para-la-cultura"
        night.ticketUrl shouldBe "https://www.eventim-light.com/de/a/617fcc080395395ac1542abe/e/6a858cff399f408f1701d052"
        night.imageUrl?.startsWith("https://tiffany-berlin.de/wp-content/uploads/") shouldBe true
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `takes the year from the weekday, also where the slug carries no date`() {
        // The 4 October repeat of Unfallkind sits at /event/michael-jaeger-unfallkind/.
        val repeat = on(LocalDate.of(2026, 10, 4), "Michael JÄGER")
        repeat.sourceId shouldBe "tiffany_club:michael-jaeger-unfallkind"
        on(LocalDate.of(2027, 1, 8), "Comedyflash").sourceId shouldBe "tiffany_club:08-01-2027-comedyflash-comedyflash-berlin-tiffany"
    }

    @Test
    fun `types a comedy night as comedy and a concert by its act`() {
        on(LocalDate.of(2026, 10, 10), "Lachkater").eventType shouldBe EventType.COMEDY.name
        on(LocalDate.of(2026, 10, 16), "Comedyflash").artists.shouldBeEmpty()

        val concert = on(LocalDate.of(2026, 11, 7), "QUEST PISTOLS")
        concert.eventType shouldBe EventType.CONCERT.name
        concert.artists shouldContainExactly listOf(ScrapedArtist("QUEST PISTOLS", titleDerived = true))
    }

    @Test
    fun `leaves out private bookings and speed dating`() {
        val titles = events.map { it.title.lowercase() }
        titles shouldNotContain "private event"
        titles shouldNotContain "matching night"
    }

    @Test
    fun `reads only the TICKETS button, not the guest-list one`() {
        // GÄSTELISTE and RESERVIERUNG link back to the event page, where they open a form.
        events.filter { it.ticketUrl == it.sourceUrl }.shouldBeEmpty()
        events.filter { it.ticketUrl == null }.shouldBeEmpty()
    }

    @Test
    fun `returns nothing for a page without a loop`() {
        TiffanyClubOverviewPageScraper(clock)
            .scrape(Jsoup.parse("<html><body></body></html>", sourceUrl), sourceUrl)
            .shouldBeEmpty()
    }

    @Test
    fun `skips an item whose date card is unreadable`() {
        val html =
            """
            <div class="e-loop-item type-event">
              <a href="https://tiffany-berlin.de/event/x/"><div class="eventdivider"><h5>Freitag</h5></div><div><h5>Night</h5></div></a>
            </div>
            """.trimIndent()
        TiffanyClubOverviewPageScraper(clock).scrape(Jsoup.parse(html, sourceUrl), sourceUrl).shouldBeEmpty()
        events.first().subtitle shouldBe "Ersti Welcome Night Berlin"
        events.first { it.title == "Prêt-à-danser" }.subtitle.shouldBeNull()
    }
}

package de.norm.events.scraper.admiralspalast

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [AdmiralspalastDetailPageScraper] against saved production pages: a two-night run,
 * a rescheduled production carrying both of the venue's opposite-meaning notes, and a single night.
 */
class AdmiralspalastDetailPageScraperTest {
    private val scraper = AdmiralspalastDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String,
        category: String? = null
    ): List<ScrapedEvent> {
        val url = "https://www.admiralspalast.theater/veranstaltung/$slug.html"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/admiralspalast/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url, category)
    }

    @Test
    fun `turns each performance of a run into its own event`() {
        val events = scrape("admiralspalast-detail-abba.html", "abba-gold-the-concert-show-emotion")
        events shouldHaveSize 2
        events.map { it.eventDate } shouldBe listOf(LocalDate.of(2027, 1, 25), LocalDate.of(2027, 1, 26))
        // The date and the start time together distinguish one performance from another.
        events.map { it.sourceId } shouldBe
            listOf(
                "admiralspalast:abba-gold-the-concert-show-emotion-2027-01-25-1930",
                "admiralspalast:abba-gold-the-concert-show-emotion-2027-01-26-1930"
            )
    }

    @Test
    fun `gives a matinee and its evening show separate identities`() {
        // The house plays Mamma Mia twice on a Saturday and twice again on the Sunday, each
        // performance with its own ticket link. Keyed on the date alone they collided on
        // `event.source_id`, which is UNIQUE — so the matinee was dropped before it ever reached
        // the database and the app showed one show a day where the venue sells two.
        val events = scrape("admiralspalast-detail-matinee.html", "mamma-mia-das-original-musical")

        val saturday = events.filter { it.eventDate == LocalDate.of(2027, 9, 18) }
        saturday.map { it.startTime } shouldBe listOf(LocalTime.of(14, 30), LocalTime.of(19, 30))
        saturday.map { it.sourceId } shouldBe
            listOf(
                "admiralspalast:mamma-mia-das-original-musical-2027-09-18-1430",
                "admiralspalast:mamma-mia-das-original-musical-2027-09-18-1930"
            )

        // Every id in the run is distinct — the property that actually matters, since a repeat
        // silently costs a performance rather than failing.
        events.map { it.sourceId }.toSet() shouldHaveSize events.size
    }

    @Test
    fun `reads the fields the venue publishes for a performance`() {
        val event = scrape("admiralspalast-detail-abba.html", "abba-gold-the-concert-show-emotion", "Show").first()
        event.title shouldBe "ABBA Gold - The Concert Show #Emotion"
        event.startTime shouldBe LocalTime.of(19, 30)
        event.eventType shouldBe EventType.SHOW.name
        // The category drives the type only — it names a staging format, not a musical style.
        event.genre shouldBe null
        event.status shouldBe EventStatus.SCHEDULED.name
        event.ticketUrl.shouldNotBeNull().shouldStartWith("https://www.eventim.de/")
        event.imageUrl.shouldNotBeNull().shouldStartWith("https://www.admiralspalast.theater/assets/images/")
        // The venue publishes no prices anywhere — tickets are sold on Eventim.
        event.pricePresale shouldBe null
        event.priceBoxOffice shouldBe null
    }

    @Test
    fun `postpones the abandoned date but leaves its replacement scheduled`() {
        val events = scrape("admiralspalast-detail-rescheduled.html", "alexander-stevens-constantin-schreiber")
        events shouldHaveSize 2

        val abandoned = events.first { it.eventDate == LocalDate.of(2026, 9, 15) }
        abandoned.subtitle shouldBe "verschoben auf 22.11.2027"
        abandoned.status shouldBe EventStatus.POSTPONED.name

        // "verlegt vom …" marks the date the show actually plays; the shared vocabulary would read
        // any "verlegt" as RELOCATED and mark the one certain date as moved.
        val replacement = events.first { it.eventDate == LocalDate.of(2027, 11, 22) }
        replacement.subtitle shouldBe "verlegt vom 15.09.2026"
        replacement.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `maps the venue's category onto an event type`() {
        val comedy = scrape("admiralspalast-detail-single.html", "bodo-wartke-antigone", "Comedy").first()
        comedy.eventType shouldBe EventType.SHOW.name
        // "Comedy" is the venue's own category and is never stored as a genre.
        comedy.genre shouldBe null

        val konzert = scrape("admiralspalast-detail-single.html", "bodo-wartke-antigone", "Konzert").first()
        konzert.eventType shouldBe EventType.CONCERT.name

        // A production on no filter page keeps the theatre's own default rather than falling to OTHER.
        val untyped = scrape("admiralspalast-detail-single.html", "bodo-wartke-antigone").first()
        untyped.eventType shouldBe EventType.SHOW.name
        untyped.genre shouldBe null
    }

    @Test
    fun `reads a production that plays a single night`() {
        val events = scrape("admiralspalast-detail-single.html", "bodo-wartke-antigone")
        events shouldHaveSize 1
        events.first().eventDate.year shouldBe 2026
    }

    @Test
    fun `flags a performance whose ticket cell says AUSVERKAUFT`() {
        val events = scrape("admiralspalast-detail-sold-out.html", "olafur-arnalds-falling-apart-together")

        // The venue drops the Tickets link and prints "AUSVERKAUFT" in its place, so a missing link
        // is the only signal — without reading the cell the night looks merely unticketed.
        val soldOut = events.filter { it.soldOut }
        soldOut.shouldNotBeEmpty()
        soldOut.all { it.ticketUrl == null } shouldBe true
        events.filterNot { it.soldOut }.all { it.ticketUrl != null } shouldBe true
    }

    @Test
    fun `returns nothing for a page without a schedule`() {
        val url = "https://www.admiralspalast.theater/veranstaltung/none.html"
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url, null).shouldBeEmpty()
    }
}

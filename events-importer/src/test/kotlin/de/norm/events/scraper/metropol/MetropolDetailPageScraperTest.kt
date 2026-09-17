package de.norm.events.scraper.metropol

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MetropolDetailPageScraper].
 *
 * Parses static snapshots of Metropol `/event/<iso-date-slug>` pages for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class MetropolDetailPageScraperTest {
    private val scraper = MetropolDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/metropol/metropol-detail-$fixture.html")!!
                .bufferedReader()
                .readText()
        val sourceUrl = "https://metropol-berlin.de/event/$slug"
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    @Test
    fun `maps a fully populated detail page`() {
        val event = scrape("thy-art-is-murder", "2026-08-04-thy-art-is-murder")
        event.title shouldBe "Thy Art is Murder"
        event.eventType shouldBe EventType.CONCERT.name
        event.eventDate shouldBe LocalDate.of(2026, 8, 4)
        event.doorsTime shouldBe LocalTime.of(18, 0)
        event.startTime shouldBe LocalTime.of(19, 0)
        event.sourceId shouldBe "metropol:2026-08-04-thy-art-is-murder"
        event.imageUrl shouldBe
            "https://metropol-berlin.de/wp-content/uploads/2026/03/thy-art-is-murder-berlin-500x334-1.webp"
        event.ticketUrl!! shouldStartWith "https://www.eventim.de/noapp/event/thy-art-is-murder-metropol-"
        event.promoters shouldBe listOf("Trinity Music")
        event.description!! shouldContain "Thy Art Is Murder"
    }

    @Test
    fun `reads the tour line as the subtitle`() {
        scrape("mucco", "2026-09-05-mucco").subtitle shouldBe "Junges Blut"
    }

    @Test
    fun `reads the labelled doors and start times`() {
        val event = scrape("mucco", "2026-09-05-mucco")
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `keeps the venue's transposed doors and start times as labelled`() {
        // "Einlass: 20:00 // Beginn: 19:00" — reported verbatim; the shared
        // orderDoorsBeforeStart guard swaps them at the persistence boundary.
        val event = scrape("party101", "2026-09-11-party101")
        event.doorsTime shouldBe LocalTime.of(20, 0)
        event.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `drops an unset start time the venue renders as midnight`() {
        val event = scrape("shadow-of-intent", "2026-10-08-shadow-of-intent")
        event.doorsTime shouldBe LocalTime.of(18, 0)
        event.startTime.shouldBeNull()
        event.subtitle shouldBe "IMPERIUM DELIRIUM EUROPEAN TOUR 2026"
    }

    @Test
    fun `reads a doors-less party that lists only a start time`() {
        val event = scrape("frank-martini-party", "2026-11-21-frank-martini-party-of-the-century")
        event.eventType shouldBe EventType.PARTY.name
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime.shouldBeNull()
        event.promoters shouldBe listOf("Frank Martini")
        // Not every ticket link is Eventim — this one sells through the promoter's own shop.
        event.ticketUrl shouldBe "https://tickets.frankmartini.se/events/frankmartinientertainmentab/2121800"
    }

    @Test
    fun `marks a cancelled show from its alert-red badge`() {
        scrape("loi", "2026-10-13-loi").status shouldBe EventStatus.CANCELLED.name
    }

    @Test
    fun `strips the relocation prefix and marks a show that moved out of the house`() {
        val event = scrape("brkn", "2026-10-04-brkn")
        event.title shouldBe "BRKN"
        event.status shouldBe EventStatus.RELOCATED.name
        // The prefix names where to; the boundary stores it (#1551).
        event.toEventEntity(venueId = 1L, venueSlug = "metropol", eventSourceId = 1L).relocatedTo shouldBe "Bi Nuu"
    }

    @Test
    fun `keeps a show that moved into the house scheduled`() {
        // The .alert-blue prose says "vom Gretchen ins Metropol verlegt" — the show happens
        // here, so only the badge and the title prefix may set the status.
        scrape("mucco", "2026-09-05-mucco").status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `derives no artists because the heading names the headliner alone`() {
        // The support acts live only on the listing; the importer rebuilds the roster there.
        scrape("thy-art-is-murder", "2026-08-04-thy-art-is-murder").artists shouldBe emptyList()
    }

    @Test
    fun `returns null for a page without a title`() {
        val sourceUrl = "https://metropol-berlin.de/event/2026-08-04-thy-art-is-murder"
        val document = Jsoup.parse("<html><body><article></article></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}

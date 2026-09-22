package de.norm.events.scraper.metropol

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MetropolOverviewPageScraper].
 *
 * Parses a static snapshot of Metropol's `/events` listing for deterministic, offline-safe
 * testing without HTTP fetching.
 */
class MetropolOverviewPageScraperTest {
    private val scraper = MetropolOverviewPageScraper()
    private val baseUrl = "https://metropol-berlin.de/events"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/metropol/metropol-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every event row on the listing`() {
        events shouldHaveSize 41
    }

    @Test
    fun `maps a fully populated row`() {
        val thyArt = event("metropol:2026-08-04-thy-art-is-murder")
        thyArt.title shouldBe "Thy Art is Murder"
        thyArt.eventType shouldBe EventType.CONCERT.name
        thyArt.eventDate shouldBe LocalDate.of(2026, 8, 4)
        thyArt.startTime shouldBe LocalTime.of(19, 0)
        thyArt.doorsTime shouldBe LocalTime.of(18, 0)
        thyArt.sourceUrl shouldBe "https://metropol-berlin.de/event/2026-08-04-thy-art-is-murder"
        thyArt.status shouldBe EventStatus.SCHEDULED.name
        thyArt.subtitle shouldBe "Fit For An Autopsy + Sun Eater + Protest The Hero"
    }

    @Test
    fun `reads the start time from the time element and doors from its nested small`() {
        // The row renders "20:00 <small>Einlass: 19:00</small>" — the own text is the start.
        val mucco = event("metropol:2026-09-05-mucco")
        mucco.startTime shouldBe LocalTime.of(20, 0)
        mucco.doorsTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `keeps the venue's transposed doors and start times as listed`() {
        // The venue lists "19:00 Einlass: 20:00" here; the shared orderDoorsBeforeStart guard
        // swaps them at the persistence boundary, so the scraper reports them verbatim.
        val party101 = event("metropol:2026-09-11-party101")
        party101.startTime shouldBe LocalTime.of(19, 0)
        party101.doorsTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `drops an unset start time the venue renders as midnight`() {
        val shadowOfIntent = event("metropol:2026-10-08-shadow-of-intent")
        shadowOfIntent.startTime.shouldBeNull()
        shadowOfIntent.doorsTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `builds the headliner and the plus-joined support acts`() {
        val thyArt = event("metropol:2026-08-04-thy-art-is-murder")
        thyArt.artists.map { it.name } shouldBe
            listOf("Thy Art is Murder", "Fit For An Autopsy", "Sun Eater", "Protest The Hero")
        thyArt.artists.map { it.role } shouldBe listOf("HEADLINER", "SUPPORT", "SUPPORT", "SUPPORT")
    }

    @Test
    fun `drops the role label the venue prints in front of its support act`() {
        // `Support: Crag Finn` on the listing minted an artist under the label on thirteen nights (#1678).
        val mountainGoats = event("metropol:2026-10-11-the-mountain-goats")
        mountainGoats.artists.map { it.name } shouldBe listOf("The Mountain Goats", "Crag Finn")
        mountainGoats.artists.map { it.role } shouldBe listOf("HEADLINER", "SUPPORT")
    }

    @Test
    fun `mints the title as the headliner for a support-less concert`() {
        event("metropol:2026-09-06-khamari").artists.map { it.name } shouldBe listOf("Khamari")
    }

    @Test
    fun `types the venue's party and derives no artists from its event name`() {
        val party = event("metropol:2026-11-21-frank-martini-party-of-the-century")
        party.eventType shouldBe EventType.PARTY.name
        party.artists.shouldBeEmpty()
        // A party row carries a bare start time and no Einlass line.
        party.startTime shouldBe LocalTime.of(20, 0)
        party.doorsTime.shouldBeNull()
    }

    @Test
    fun `marks a cancelled show from its attention badge`() {
        event("metropol:2026-10-13-loi").status shouldBe EventStatus.CANCELLED.name
    }

    @Test
    fun `strips the relocation prefix and marks a show that moved out of the house`() {
        val brkn = event("metropol:2026-10-04-brkn")
        brkn.title shouldBe "BRKN"
        brkn.status shouldBe EventStatus.RELOCATED.name
        brkn.artists.map { it.name } shouldBe listOf("BRKN")
    }

    @Test
    fun `keeps a show that moved into the house scheduled`() {
        // Mucco's ".changes" prose also says "verlegt" — but it moved *from* Gretchen *into*
        // Metropol, so it does take place here and must not be flagged RELOCATED.
        event("metropol:2026-09-05-mucco").status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `parses the whole programme into future dates in listing order`() {
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 4)
        events.last().eventDate shouldBe LocalDate.of(2027, 3, 14)
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
    }

    @Test
    fun `publishes no prices or sold-out state`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.soldOut shouldBe false
        }
    }

    @Test
    fun `returns an empty list for a page without an event list`() {
        val document = Jsoup.parse("<html><body><div id='em-wrapper'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

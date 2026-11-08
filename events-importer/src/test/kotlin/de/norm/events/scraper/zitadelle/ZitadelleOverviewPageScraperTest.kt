package de.norm.events.scraper.zitadelle

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ZitadelleOverviewPageScraper].
 *
 * Parses a static snapshot of the Citadel Music Festival's `/events` page for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class ZitadelleOverviewPageScraperTest {
    private val scraper = ZitadelleOverviewPageScraper()
    private val baseUrl = "https://citadel-music-festival.de/events"

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(fixtureDocument("zitadelle/zitadelle-overview.html", baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every card on the listing`() {
        events shouldHaveSize 8
    }

    @Test
    fun `maps a fully populated card`() {
        val antilopen = event("zitadelle:2026-08-15-antilopen-gang")
        antilopen.title shouldBe "ANTILOPEN GANG"
        antilopen.eventType shouldBe EventType.CONCERT.name
        antilopen.eventDate shouldBe LocalDate.of(2026, 8, 15)
        antilopen.startTime shouldBe LocalTime.of(17, 0)
        antilopen.sourceUrl shouldBe "https://citadel-music-festival.de/event/2026-08-15-antilopen-gang"
        antilopen.artists.map { it.name } shouldBe listOf("ANTILOPEN GANG")
    }

    @Test
    fun `reads the poster out of the card's CSS custom property`() {
        // The listing sets the image as `style="--bg: url('…')"` rather than rendering an <img>.
        val antilopen = event("zitadelle:2026-08-15-antilopen-gang")
        antilopen.imageUrl!! shouldStartWith "https://citadel-music-festival.de/wp-content/uploads/"
        events.none { it.imageUrl == null } shouldBe true
    }

    @Test
    fun `takes the sold-out flag from data-status, never from the aria-label`() {
        // Every card's aria-label ends "– Ausverkauft" regardless of the actual state.
        events.filter { it.soldOut }.map { it.title } shouldBe
            listOf("ANTILOPEN GANG", "Trailerpark", "Trailerpark")
        event("zitadelle:2026-08-18-omd").soldOut shouldBe false
    }

    @Test
    fun `reads a relocated show as relocated, not cancelled`() {
        // The card carries both an "Abgesagt" badge and a secondary "Verlegt" marker; the show
        // moved to the Columbiahalle, so the more specific marker wins.
        val offDays = event("zitadelle:2026-08-19-off-days")
        offDays.status shouldBe EventStatus.RELOCATED.name
        events.count { it.status != EventStatus.SCHEDULED.name } shouldBe 1
    }

    @Test
    fun `keeps a production's two nights apart by the date in its slug`() {
        val trailerpark = events.filter { it.title == "Trailerpark" }
        trailerpark shouldHaveSize 2
        trailerpark.map { it.eventDate } shouldBe listOf(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 29))
        trailerpark.map { it.sourceId }.toSet() shouldHaveSize 2
    }

    @Test
    fun `parses every card into a resolved date and start time`() {
        events.none { it.eventDate == UNRESOLVED_EVENT_DATE } shouldBe true
        events.none { it.startTime == null } shouldBe true
    }

    @Test
    fun `leaves the detail-only fields empty`() {
        val antilopen = event("zitadelle:2026-08-15-antilopen-gang")
        antilopen.doorsTime.shouldBeNull()
        antilopen.subtitle.shouldBeNull()
        antilopen.description.shouldBeNull()
        antilopen.ticketUrl.shouldBeNull()
        antilopen.promoters.shouldBeEmpty()
    }

    @Test
    fun `publishes no prices anywhere on the listing`() {
        events.forEach {
            it.pricePresale.shouldBeNull()
            it.priceBoxOffice.shouldBeNull()
            it.priceNote.shouldBeNull()
        }
    }

    @Test
    fun `returns an empty list for a page without cards`() {
        val document = Jsoup.parse("<html><body><div class='cmf-grid'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

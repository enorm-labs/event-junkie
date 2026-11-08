package de.norm.events.scraper.quasimodo

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
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
 * Unit tests for [QuasimodoOverviewPageScraper].
 *
 * Parses a static snapshot of Quasimodo's `/events` page for deterministic, offline-safe testing
 * without HTTP fetching.
 */
class QuasimodoOverviewPageScraperTest {
    private val scraper = QuasimodoOverviewPageScraper()
    private val baseUrl = "https://quasimodo.club/events"

    private val events: List<ScrapedEvent> by lazy { scraper.scrape(fixtureDocument("quasimodo/quasimodo-overview.html", baseUrl), baseUrl) }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every card across all month groups`() {
        events shouldHaveSize 26
    }

    @Test
    fun `maps a fully populated card`() {
        val otisKane = event("quasimodo:otis-kane-7410")
        otisKane.title shouldBe "Otis Kane"
        otisKane.eventType shouldBe EventType.CONCERT.name
        otisKane.eventDate shouldBe LocalDate.of(2026, 10, 8)
        otisKane.startTime shouldBe LocalTime.of(22, 0)
        otisKane.sourceUrl shouldBe "https://quasimodo.club/events/otis-kane-7410"
        otisKane.genre shouldBe "Neo-Soul, Rhythm and Blues"
        otisKane.imageUrl!! shouldStartWith "https://quasimodo.club/wp-content/uploads/"
        otisKane.ticketUrl!! shouldStartWith "https://www.eventim.de/"
        otisKane.artists.map { it.name } shouldBe listOf("Otis Kane")
    }

    @Test
    fun `reads the date and time from the mobile date block`() {
        // The desktop block abbreviates to "01." / "Aug."; the mobile one has the full
        // "01.08.2026 - 22:00", so neither the month heading nor the abbreviation is needed.
        val weLove80s = event("quasimodo:we-love-80s-37-7300")
        weLove80s.eventDate shouldBe LocalDate.of(2026, 8, 1)
        weLove80s.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `reads a start time that differs from the venue's usual 22 00`() {
        event("quasimodo:berlin-beat-invasion-no-8-7316").startTime shouldBe LocalTime.of(22, 30)
    }

    @Test
    fun `joins the venue's genre tags`() {
        event("quasimodo:marcos-coll-album-release-concert-7387").genre shouldBe "Blues, Jazz, Latin, Latin Jazz"
    }

    @Test
    fun `leaves the genre empty when the venue tagged none`() {
        event("quasimodo:berlin-beat-invasion-no-8-7316").genre.shouldBeNull()
    }

    @Test
    fun `keeps a recurring series apart by the post id in each slug`() {
        val weLove80s = events.filter { it.title == "WE LOVE 80S" }
        weLove80s shouldHaveSize 5
        weLove80s.map { it.sourceId }.toSet() shouldHaveSize 5
        weLove80s.map { it.eventDate } shouldBe
            listOf(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 12),
                LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 11, 7),
                LocalDate.of(2026, 12, 5)
            )
    }

    @Test
    fun `leaves the detail-only fields empty`() {
        val otisKane = event("quasimodo:otis-kane-7410")
        otisKane.doorsTime.shouldBeNull()
        otisKane.pricePresale.shouldBeNull()
        otisKane.priceBoxOffice.shouldBeNull()
        otisKane.description.shouldBeNull()
        otisKane.promoters.shouldBeEmpty()
    }

    @Test
    fun `parses every card into a resolved date in listing order`() {
        events.none { it.eventDate == de.norm.events.scraper.UNRESOLVED_EVENT_DATE } shouldBe true
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
    }

    @Test
    fun `returns an empty list for a page without cards`() {
        val document = Jsoup.parse("<html><body><div class='em-events-list'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

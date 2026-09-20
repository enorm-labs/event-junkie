package de.norm.events.scraper.morphine

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [MorphineOverviewPageScraper].
 *
 * Uses a real snapshot of the `/events` listing, whose last row is the dateless `ARCHIVE`
 * navigation link rather than an event.
 */
class MorphineOverviewPageScraperTest {
    private val scraper = MorphineOverviewPageScraper()
    private val baseUrl = "http://www.morphinerecords.com/events"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/morphine/morphine-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceIdSuffix: String): ScrapedEvent = events.first { it.sourceId == "morphine:$sourceIdSuffix" }

    @Test
    fun `parses every dated listing row`() {
        events shouldHaveSize 11
    }

    @Test
    fun `skips the dateless ARCHIVE navigation row`() {
        events.none { it.title == "ARCHIVE" } shouldBe true
        events.none { it.sourceUrl.endsWith("/ARCHIVE") } shouldBe true
    }

    @Test
    fun `parses the date, title and identity of a listing row`() {
        val event = event("sardy-fardy-live-recording")

        event.title shouldBe "Sardy Fardy - Live Recording"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 8, 7)
        event.sourceUrl shouldBe "http://www.morphinerecords.com/events/sardy-fardy-live-recording"
        event.artists shouldBe listOf(ScrapedArtist("Sardy Fardy", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `leaves the detail-only fields empty`() {
        val event = event("raphael-roginski")

        event.doorsTime shouldBe null
        event.startTime shouldBe null
        event.description shouldBe null
        event.imageUrl shouldBe null
        event.priceNote shouldBe null
    }

    @Test
    fun `keeps the listing in date order across the month boundary`() {
        events.first().eventDate shouldBe LocalDate.of(2026, 8, 7)
        events.last().eventDate shouldBe LocalDate.of(2026, 9, 26)
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
    }

    @Test
    fun `strips the Live Recording framing from the title-derived headliner but keeps the title`() {
        val event = event("invisible-weather")

        event.title shouldBe "Invisible Weather (Kakaliagou/ Thieke/ Yassin) - Live Recording"
        event.artists shouldBe listOf(ScrapedArtist("Invisible Weather (Kakaliagou/ Thieke/ Yassin)", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `resolves the html entity in a title`() {
        event("pici-clemence-manachere-polina-pohozha-live-recording").title shouldBe
            "PICI - Clémence Manachère & Polina Pohozha - Live Recording"
    }

    @Test
    fun `returns no events for a page without a listing`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl) shouldHaveSize 0
    }

    @Test
    fun `derives a stable sourceId from the detail URL slug`() {
        events.forEach { it.sourceId.shouldNotBeNull() }
        events.map { it.sourceId }.toSet() shouldHaveSize events.size
        event("vinyl-reduction-2").eventDate shouldBe LocalDate.of(2026, 9, 5)
    }
}

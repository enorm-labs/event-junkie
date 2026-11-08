package de.norm.events.scraper.loge

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [LogeDetailPageScraper].
 *
 * Parses static snapshots of Loge `/event-details/<slug>` pages, whose
 * authoritative data lives in a schema.org `Event` JSON-LD block.
 */
class LogeDetailPageScraperTest {
    private val scraper = LogeDetailPageScraper()

    private fun scrape(
        fixture: String,
        sourceUrl: String
    ) = scraper.scrape(fixtureDocument("loge/$fixture", sourceUrl), sourceUrl)

    @Test
    fun `parses the price, date, and status from the JSON-LD`() {
        val url = "https://www.loge-berlin.org/event-details/estamoe-daloy-furie"
        val event = scrape("loge-detail-estamoe.html", url)!!

        event.title shouldBe "ESTAMOE + DALOY! + FURIE"
        event.eventDate shouldBe LocalDate.of(2026, 7, 17)
        event.startTime shouldBe LocalTime.of(19, 0)
        event.pricePresale shouldBe BigDecimal("12.30")
        event.status shouldBe "SCHEDULED"
        event.sourceId shouldBe "loge:estamoe-daloy-furie"
    }

    @Test
    fun `leaves the artist roster to the overview page`() {
        val url = "https://www.loge-berlin.org/event-details/estamoe-daloy-furie"
        scrape("loge-detail-estamoe.html", url)!!.artists.shouldBeEmpty()
    }

    @Test
    fun `maps a cancelled event with no offers`() {
        val url = "https://www.loge-berlin.org/event-details/ka-oh-zweikant"
        val event = scrape("loge-detail-cancelled.html", url)!!

        event.status shouldBe "CANCELLED"
        event.pricePresale.shouldBeNull()
        event.eventDate shouldBe LocalDate.of(2026, 8, 14)
    }

    @Test
    fun `returns null when the page has no JSON-LD event`() {
        val document = Jsoup.parse("<html><body><p>no data</p></body></html>", "https://www.loge-berlin.org/event-details/x")
        scraper.scrape(document, "https://www.loge-berlin.org/event-details/x").shouldBeNull()
    }
}

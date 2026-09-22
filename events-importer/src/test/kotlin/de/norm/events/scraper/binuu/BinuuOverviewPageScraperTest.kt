package de.norm.events.scraper.binuu

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [BinuuOverviewPageScraper].
 *
 * Parses a static snapshot of Bi Nuu's `/de/events` listing (whose events live
 * in the embedded SvelteKit `data.events[]` payload) for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class BinuuOverviewPageScraperTest {
    private val scraper = BinuuOverviewPageScraper()
    private val baseUrl = "https://binuu.de/de/events"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/binuu/binuu-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every event in the listing payload`() {
        events shouldHaveSize 39
    }

    @Test
    fun `builds a stable sourceId and detail URL from the event id`() {
        val grooveJet = event("binuu:zf0kroyf2cjolyl")
        grooveJet.title shouldBe "GrooveJet Berlin"
        grooveJet.sourceUrl shouldBe "https://binuu.de/de/events/zf0kroyf2cjolyl"
    }

    @Test
    fun `parses the date and start time from the ISO timestamp with year`() {
        val grooveJet = event("binuu:zf0kroyf2cjolyl")
        grooveJet.eventDate shouldBe LocalDate.of(2026, 7, 11)
        // `20:00:00.000Z` in July is 22:00 in Berlin, which is what the venue prints (#1675).
        grooveJet.startTime shouldBe LocalTime.of(22, 0)
    }

    @Test
    fun `prefixes the PocketBase file base onto relative image URLs`() {
        event("binuu:zf0kroyf2cjolyl").imageUrl!! shouldStartWith "https://pb.binuu.de/api/files/"
    }

    @Test
    fun `captures the sold-out flag and the tour subtitle`() {
        val archEnemy = event("binuu:inzpqdgvi1eab2q")
        archEnemy.title shouldBe "Arch Enemy"
        archEnemy.soldOut shouldBe true
        archEnemy.subtitle shouldBe "Back To The Root Of All Evil"
    }

    @Test
    fun `maps the relocated status code`() {
        val oidorno = event("binuu:fko44tarc3g5wlv")
        oidorno.status shouldBe "RELOCATED"
    }

    @Test
    fun `leaves the roster to the detail page`() {
        // The overview payload has no performers; artists are built on the detail page.
        event("binuu:inzpqdgvi1eab2q").artists.shouldBeEmpty()
    }

    @Test
    fun `returns an empty list when the page has no events payload`() {
        val doc = Jsoup.parse("<html><body></body></html>", baseUrl)
        scraper.scrape(doc, baseUrl).shouldBeEmpty()
    }
}

package de.norm.events.scraper.loge

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [LogeOverviewPageScraper].
 *
 * Parses a static snapshot of Loge's `/event-list` page (whose events live in
 * the embedded `wix-warmup-data` JSON) for deterministic, offline-safe testing
 * without HTTP fetching.
 */
class LogeOverviewPageScraperTest {
    private val scraper = LogeOverviewPageScraper()
    private val baseUrl = "https://www.loge-berlin.org/event-list"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/loge/loge-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `tags every show Punk, the venue's sound, since it names no style`() {
        events.map { it.genre }.distinct() shouldBe listOf("Punk")
    }

    @Test
    fun `discovers every event in the warmup payload`() {
        events shouldHaveSize 9
    }

    @Test
    fun `maps a fully populated event`() {
        val estamoe = event("loge:estamoe-daloy-furie")
        estamoe.title shouldBe "ESTAMOE + DALOY! + FURIE"
        estamoe.eventType shouldBe EventType.CONCERT.name
        estamoe.eventDate shouldBe LocalDate.of(2026, 7, 17)
        estamoe.startTime shouldBe LocalTime.of(19, 0)
        // `endDate: 2026-07-17T20:00:00.000Z` is 22:00 Berlin, the same evening (ADR-029).
        estamoe.endDate shouldBe LocalDate.of(2026, 7, 17)
        estamoe.endTime shouldBe LocalTime.of(22, 0)
        estamoe.sourceUrl shouldBe "https://www.loge-berlin.org/event-details/estamoe-daloy-furie"
        estamoe.imageUrl shouldBe "https://static.wixstatic.com/media/c8299d_5471b0b2807a43cfbebf4583469519dc~mv2.jpg"
    }

    @Test
    fun `converts the UTC start instant to the Berlin wall-clock time`() {
        // startDate is 2026-10-09T17:00:00Z; in Europe/Berlin (summer time) that is 19:00.
        val mentalRiot = event("loge:mental-riot-soli-festival")
        mentalRiot.eventDate shouldBe LocalDate.of(2026, 10, 9)
        mentalRiot.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `builds headliner and support roles from the plus-joined title`() {
        val estamoe = event("loge:estamoe-daloy-furie")
        estamoe.artists.map { it.name } shouldBe listOf("ESTAMOE", "DALOY!", "FURIE")
        estamoe.artists.map { it.role } shouldBe listOf("HEADLINER", "SUPPORT", "SUPPORT")
    }

    @Test
    fun `drops the placeholder support label leaving only the headliner`() {
        val moriBlau = event("loge:mori-blau-support")
        moriBlau.title shouldBe "MORI BLAU + Support"
        moriBlau.artists.map { it.name } shouldBe listOf("MORI BLAU")
    }

    @Test
    fun `extracts no artists from a title without a support separator`() {
        // "MENTAL RIOT (Soli-Festival)" has no "+", so a single segment is not treated as an artist.
        event("loge:mental-riot-soli-festival").artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the price to the detail page`() {
        event("loge:estamoe-daloy-furie").pricePresale shouldBe null
    }

    @Test
    fun `returns an empty list when the page has no warmup payload`() {
        val document = Jsoup.parse("<html><body></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

package de.norm.events.scraper.maxxim

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MaxximOverviewPageScraper].
 *
 * Parses a static snapshot of MAXXIM's `/partys` page (whose events live in the
 * embedded `wix-warmup-data` JSON) for deterministic, offline-safe testing
 * without HTTP fetching. The states the live programme did not happen to show —
 * cancellation, sold out, tiered prices, missing slug/date — come from the
 * hand-crafted `maxxim-overview-edge-cases.html` variant.
 */
class MaxximOverviewPageScraperTest {
    private val scraper = MaxximOverviewPageScraper()
    private val baseUrl = "https://www.maxxim-berlin.de/partys"

    private val events: List<ScrapedEvent> by lazy { parse("maxxim-overview.html") }
    private val edgeCaseEvents: List<ScrapedEvent> by lazy { parse("maxxim-overview-edge-cases.html") }

    private fun parse(fixture: String): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/maxxim/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    private fun edgeCaseEvent(sourceId: String): ScrapedEvent = edgeCaseEvents.first { it.sourceId == sourceId }

    @Test
    fun `discovers every event in the warmup payload`() {
        events shouldHaveSize 18
    }

    @Test
    fun `maps a fully populated event`() {
        val millenium = event("maxxim:millenium-memories-90s-2000er-4")
        millenium.title shouldBe "MILLENIUM MEMORIES - 90s/2000er"
        millenium.eventType shouldBe EventType.PARTY.name
        millenium.eventDate shouldBe LocalDate.of(2026, 8, 1)
        millenium.startTime shouldBe LocalTime.of(22, 0)
        // `endDate: 2026-08-02T03:00:00.000Z` is 05:00 Berlin, the club's real closing (ADR-029).
        millenium.endDate shouldBe LocalDate.of(2026, 8, 2)
        millenium.endTime shouldBe LocalTime.of(5, 0)
        millenium.sourceUrl shouldBe "https://www.maxxim-berlin.de/event-details/millenium-memories-90s-2000er-4"
        millenium.imageUrl shouldBe "https://static.wixstatic.com/media/8948df_f54d38631a5a4644bf9b6c0caa656be5~mv2.png"
        millenium.pricePresale shouldBe BigDecimal("12.00")
        millenium.soldOut shouldBe false
        millenium.status shouldBe EventStatus.SCHEDULED.name
        millenium.description?.startsWith("Bereit fuer die beste Zeitreise") shouldBe true
    }

    @Test
    fun `converts the UTC start instant to the Berlin wall-clock time`() {
        // startDate is 2026-08-04T17:00:00Z; in Europe/Berlin (summer time) that is 19:00 — an
        // after-work party, not the 17:00 UTC value.
        val afterWork = event("maxxim:far-out-after-work-11")
        afterWork.eventDate shouldBe LocalDate.of(2026, 8, 4)
        afterWork.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `types every night as a party and derives no artists`() {
        events.map { it.eventType }.toSet() shouldBe setOf(EventType.PARTY.name)
        // "Queens Night - SHERY M live" names a guest inside free text only; nothing is extracted.
        event("maxxim:queens-night-shery-m-live").artists.shouldBeEmpty()
    }

    @Test
    fun `builds a stable source id from the event slug`() {
        // The two Monday nights share a title, so only the slug keeps their sourceIds apart.
        val mondays = events.filter { it.title == "MONDAY NITE CLUB" }
        mondays.map { it.sourceId }.toSet() shouldHaveSize mondays.size
        mondays.map { it.sourceId } shouldBe
            listOf("maxxim:monday-nite-club-202", "maxxim:monday-nite-club-199", "maxxim:monday-nite-club-201")
    }

    @Test
    fun `leaves no price note for a single-tier night`() {
        event("maxxim:the-maxxim-sunday-club-7").priceNote.shouldBeNull()
    }

    @Test
    fun `reads a decimal ticket price`() {
        event("maxxim:replay-back-to-the-90s-2000s-2010s-party-6").pricePresale shouldBe BigDecimal("12.30")
    }

    @Test
    fun `sets no external ticket url because tickets are sold on the wix event page`() {
        events.forEach { it.ticketUrl.shouldBeNull() }
    }

    @Test
    fun `marks a cancelled night`() {
        val cancelled = edgeCaseEvent("maxxim:summer-closing-abgesagt")
        cancelled.status shouldBe EventStatus.CANCELLED.name
    }

    @Test
    fun `marks a sold-out night and notes its price range`() {
        val birthday = edgeCaseEvent("maxxim:maxxim-birthday")
        birthday.soldOut shouldBe true
        birthday.pricePresale shouldBe BigDecimal("10.00")
        birthday.priceNote shouldBe "€10 – €25"
    }

    @Test
    fun `keeps a zero-price night at zero so free entry is derived downstream`() {
        edgeCaseEvent("maxxim:open-house").pricePresale shouldBe BigDecimal("0.00")
    }

    @Test
    fun `skips entries without a slug or a resolvable date`() {
        edgeCaseEvents.map { it.title } shouldBe
            listOf("SUMMER CLOSING - ABGESAGT", "MAXXIM BIRTHDAY - EARLY BIRD & LATE ENTRY", "OPEN HOUSE")
    }

    @Test
    fun `returns an empty list when the page has no warmup payload`() {
        val document = Jsoup.parse("<html><body></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

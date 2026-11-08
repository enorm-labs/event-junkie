package de.norm.events.scraper.wuhlheide

import de.norm.events.event.EventStatus
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

/**
 * Unit tests for [WuhlheideOverviewPageScraper].
 *
 * Parses a static snapshot of Parkbühne Wuhlheide's `/programm` page for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class WuhlheideOverviewPageScraperTest {
    private val scraper = WuhlheideOverviewPageScraper()
    private val baseUrl = "https://www.wuhlheide.de/programm"

    private val events: List<ScrapedEvent> by lazy {
        scraper.scrape(fixtureDocument("wuhlheide/wuhlheide-overview.html", baseUrl), baseUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `discovers every show across both year sections`() {
        // The page splits the season into "Konzerte 2026" and "Konzerte 2027" blocks.
        events shouldHaveSize 16
        events.count { it.eventDate.year == 2027 } shouldBe 4
    }

    @Test
    fun `maps a fully populated show`() {
        val alligatoah = event("wuhlheide:alligatoah/2026-08-01")
        alligatoah.title shouldBe "Alligatoah"
        alligatoah.subtitle shouldBe "20 Jahre - Jubiläumskonzert"
        alligatoah.eventType shouldBe EventType.CONCERT.name
        alligatoah.eventDate shouldBe LocalDate.of(2026, 8, 1)
        alligatoah.sourceUrl shouldBe "https://www.wuhlheide.de/programm/alligatoah/2026-08-01"
        alligatoah.imageUrl!! shouldStartWith "https://www.wuhlheide.de/storage/app/uploads/"
        alligatoah.ticketUrl!! shouldStartWith "https://www.ticketmaster.de/event/"
        alligatoah.soldOut shouldBe false
        alligatoah.status shouldBe EventStatus.SCHEDULED.name
        alligatoah.artists.map { it.name } shouldBe listOf("Alligatoah")
    }

    @Test
    fun `takes the date from the event URL rather than the German long date`() {
        // The page renders "Sonntag, 06. Juni 2027"; the URL already carries 2027-06-06.
        event("wuhlheide:die-aerzte/2027-06-06").eventDate shouldBe LocalDate.of(2027, 6, 6)
    }

    @Test
    fun `restores an act name broken by a word-break hint`() {
        // The markup is "AnnenMay<wbr>Kantereit" — one word, not two.
        val show = event("wuhlheide:annenmay-wbr-kantereit/2026-08-13")
        show.title shouldBe "AnnenMayKantereit"
        show.artists.map { it.name } shouldBe listOf("AnnenMayKantereit")
    }

    @Test
    fun `keeps a run of nights by one act apart by their per-date URLs`() {
        val ninaChuba = events.filter { it.title == "Nina Chuba" }
        ninaChuba shouldHaveSize 3
        ninaChuba.map { it.eventDate } shouldBe
            listOf(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13))
        ninaChuba.map { it.sourceId }.toSet() shouldHaveSize 3
    }

    @Test
    fun `flags a sold-out show and leaves it scheduled`() {
        val kraftklub = event("wuhlheide:kraftklub/2026-08-21")
        kraftklub.soldOut shouldBe true
        // "Ausverkauft" is a flag, not a status.
        kraftklub.status shouldBe EventStatus.SCHEDULED.name
        // A sold-out show drops its ticket link.
        kraftklub.ticketUrl.shouldBeNull()
    }

    @Test
    fun `reads the lower-case spelling of the sold-out badge too`() {
        event("wuhlheide:kraftklub/2026-08-22").soldOut shouldBe true
    }

    @Test
    fun `splits a co-billed act into its two headliners`() {
        event("wuhlheide:bonez-mc-raf-camora/2027-08-07").artists.map { it.name } shouldBe
            listOf("Bonez MC", "Raf Camora")
    }

    @Test
    fun `leaves the detail-only fields empty`() {
        val alligatoah = event("wuhlheide:alligatoah/2026-08-01")
        alligatoah.doorsTime.shouldBeNull()
        alligatoah.startTime.shouldBeNull()
        alligatoah.pricePresale.shouldBeNull()
        alligatoah.promoters.shouldBeEmpty()
    }

    @Test
    fun `parses every show into a resolved future date in listing order`() {
        events.none { it.eventDate == de.norm.events.scraper.UNRESOLVED_EVENT_DATE } shouldBe true
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
    }

    @Test
    fun `returns an empty list for a page without shows`() {
        val document = Jsoup.parse("<html><body><div class='shows'></div></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}

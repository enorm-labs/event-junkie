package de.norm.events.scraper.wuhlheide

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [WuhlheideDetailPageScraper].
 *
 * Parses static snapshots of Parkbühne Wuhlheide `/programm/<act>/YYYY-MM-DD` pages for
 * deterministic, offline-safe testing without HTTP fetching.
 */
class WuhlheideDetailPageScraperTest {
    private val scraper = WuhlheideDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val sourceUrl = "https://www.wuhlheide.de/programm/$slug"
        return scraper.scrape(fixtureDocument("wuhlheide/wuhlheide-detail-$fixture.html", sourceUrl), sourceUrl)!!
    }

    @Test
    fun `maps a fully populated detail page`() {
        val alligatoah = scrape("alligatoah", "alligatoah/2026-08-01")
        alligatoah.title shouldBe "Alligatoah"
        alligatoah.eventType shouldBe EventType.CONCERT.name
        alligatoah.eventDate shouldBe LocalDate.of(2026, 8, 1)
        alligatoah.doorsTime shouldBe LocalTime.of(17, 0)
        alligatoah.startTime shouldBe LocalTime.of(19, 0)
        alligatoah.pricePresale shouldBe BigDecimal("69.90")
        alligatoah.promoters shouldBe listOf("Boldt Berlin Konzertagentur")
        alligatoah.sourceId shouldBe "wuhlheide:alligatoah/2026-08-01"
        alligatoah.ticketUrl!! shouldStartWith "https://www.ticketmaster.de/event/"
        alligatoah.imageUrl!! shouldStartWith "https://www.wuhlheide.de/storage/app/uploads/"
    }

    @Test
    fun `parses the German decimal comma in a EUR price`() {
        // The venue spells the currency out ("69,90 EUR"), so the shared euro-sign parser misses it.
        scrape("alligatoah", "alligatoah/2026-08-01").pricePresale shouldBe BigDecimal("69.90")
    }

    @Test
    fun `omits price and ticket link for a sold-out show`() {
        val kantereit = scrape("annenmaykantereit", "annenmay-wbr-kantereit/2026-08-13")
        kantereit.pricePresale.shouldBeNull()
        kantereit.ticketUrl.shouldBeNull()
        // The times are still published.
        kantereit.doorsTime shouldBe LocalTime.of(17, 0)
        kantereit.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `restores an act name broken by a word-break hint`() {
        scrape("annenmaykantereit", "annenmay-wbr-kantereit/2026-08-13").title shouldBe "AnnenMayKantereit"
    }

    @Test
    fun `never reads the detail heading as a subtitle`() {
        // This page's h3 is an admin notice ("Bitte die Altersbeschränkungen beachten:"), not a
        // tour name — the listing owns the subtitle instead.
        scrape("annenmaykantereit", "annenmay-wbr-kantereit/2026-08-13").subtitle.shouldBeNull()
    }

    @Test
    fun `reads the promoter from the Veranstalter block`() {
        scrape("die-aerzte", "die-aerzte/2027-06-05").promoters shouldBe listOf("KKT GmbH")
    }

    @Test
    fun `reads times of a show whose doors and start differ from the default`() {
        val dieAerzte = scrape("die-aerzte", "die-aerzte/2027-06-05")
        dieAerzte.doorsTime shouldBe LocalTime.of(17, 30)
        dieAerzte.startTime shouldBe LocalTime.of(20, 0)
        dieAerzte.eventDate shouldBe LocalDate.of(2027, 6, 5)
    }

    @Test
    fun `returns null for a page without a title`() {
        val sourceUrl = "https://www.wuhlheide.de/programm/alligatoah/2026-08-01"
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}

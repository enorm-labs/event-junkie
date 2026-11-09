package de.norm.events.scraper.labsaal

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Unit tests for [LabsaalEventPageScraper], against the cost block of a Modern Events Calendar page. */
class LabsaalEventPageScraperTest {
    private val scraper = LabsaalEventPageScraper()

    private val row =
        ScrapedEvent(
            title = "Night",
            eventDate = LocalDate.of(2026, 11, 29),
            sourceUrl = "https://labsaal.de/events/night/",
            sourceId = "labsaal:night"
        )

    private fun page(cost: String?): Document {
        val block =
            cost
                ?.let {
                    """<div class="mec-event-cost"><i class="mec-sl-wallet"></i><div class="mec-cost">Preise</div>
                      <dl><dd class="mec-events-event-cost">$it</dd></dl></div>"""
                }.orEmpty()
        return Jsoup.parse("""<div class="mec-wrap"><div class="mec-event-meta">$block</div></div>""", row.sourceUrl)
    }

    @Test
    fun `an Eintritt frei line marks the night free and stores no note`() {
        val event = scraper.enrich(row, page("Eintritt frei")).shouldNotBeNull()

        event.free shouldBe true
        event.priceNote.shouldBeNull()
    }

    @Test
    fun `unlabelled tiers are kept as the note, with no presale or box office price`() {
        val event = scraper.enrich(row, page("16,- / 14,- / 10,-")).shouldNotBeNull()

        event.priceNote shouldBe "16,- / 14,- / 10,-"
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.free shouldBe false
    }

    @Test
    fun `tiers on the page override a free-entry phrase in the feed's prose`() {
        val event = scraper.enrich(row.copy(free = true), page("20,-/ 18,-/ 10,-")).shouldNotBeNull()

        event.priceNote shouldBe "20,-/ 18,-/ 10,-"
        event.free shouldBe false
    }

    @Test
    fun `a page without a cost line or with a line without figures keeps the feed's fields`() {
        val feedRow = row.copy(pricePresale = BigDecimal("12.00"))

        scraper.enrich(feedRow, page(null)) shouldBe feedRow
        scraper.enrich(feedRow, page("")) shouldBe feedRow
        scraper.enrich(feedRow, page("siehe Text")) shouldBe feedRow
    }

    @Test
    fun `a page with no calendar event yields null, so the row is flagged`() {
        scraper.enrich(row, Jsoup.parse("<p>Seite nicht gefunden</p>", row.sourceUrl)).shouldBeNull()
    }
}

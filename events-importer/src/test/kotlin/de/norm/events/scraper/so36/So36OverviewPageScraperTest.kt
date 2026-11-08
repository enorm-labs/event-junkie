package de.norm.events.scraper.so36

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [So36OverviewPageScraper].
 *
 * Parses the static `/tickets` overview fixture and asserts discovery of event
 * detail URLs, id/date extraction from the product path, and title extraction
 * from the link text.
 */
class So36OverviewPageScraperTest {
    private val scraper = So36OverviewPageScraper()
    private val baseUrl = "https://www.so36.com/tickets"

    @Test
    fun `discovers every distinct event on the overview page`() {
        val events = scraper.scrape(fixtureDocument("so36/so36-overview.html", baseUrl), baseUrl)
        // 108 distinct product ids; the featured "TONIGHT" teaser repeats one of them
        // and must be deduplicated rather than double-counted.
        events shouldHaveSize 108
        events.map { it.sourceId }.toSet() shouldHaveSize 108
    }

    @Test
    fun `extracts title, date, source url and stable source id from a product link`() {
        val poisonRuin =
            scraper.scrape(fixtureDocument("so36/so36-overview.html", baseUrl), baseUrl).firstOrNull { it.sourceId == "so36:95201" }
        poisonRuin.shouldNotBeNull()
        poisonRuin.title shouldBe "POISON RUIN"
        poisonRuin.eventDate shouldBe LocalDate.of(2026, 7, 9)
        poisonRuin.sourceUrl shouldBe
            "https://www.so36.com/produkte/95201-tickets-poison-ruin-so36-berlin-am-09-07-2026"
    }

    @Test
    fun `skips non-event shop links that carry no date suffix`() {
        val html =
            """
            <html><body>
              <a href="/produkte/12345-so36-shirt">SO36 Shirt</a>
              <a href="/produkte/97683-tickets-last-night-so36-berlin-am-03-07-2026"
                 title="Tickets LAST NIGHT in Berlin am 03.07.2026">Tickets LAST NIGHT in Berlin am 03.07.2026</a>
            </body></html>
            """.trimIndent()

        val events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
        events shouldHaveSize 1
        events.single().sourceId shouldBe "so36:97683"
    }

    @Test
    fun `falls back to a placeholder title when the link text is unparseable`() {
        val html =
            """
            <html><body>
              <a href="/produkte/55555-mystery-so36-berlin-am-15-08-2026">no recognizable pattern</a>
            </body></html>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()
        event.title shouldBe "SO36 2026-08-15"
        event.eventDate shouldBe LocalDate.of(2026, 8, 15)
    }
}

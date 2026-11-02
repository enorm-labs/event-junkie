package de.norm.events.scraper.lido

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Unit tests for [LidoDetailPageScraper].
 *
 * Uses a static HTML fixture derived from a real Lido detail page for
 * deterministic, offline-safe testing without HTTP fetching.
 */
class LidoDetailPageScraperTest {
    private val scraper = LidoDetailPageScraper()
    private val sourceUrl = "https://www.lido-berlin.de/events/2026-06-15-sorry"

    private val sorry: ScrapedEvent by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/lido/lido-detail-sorry.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    @Test
    fun `parses title, type and times from the shared event header`() {
        sorry.title shouldBe "SORRY"
        sorry.eventType shouldBe "CONCERT"
        sorry.doorsTime shouldBe LocalTime.of(19, 0)
        sorry.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `derives sourceId from the URL slug`() {
        sorry.sourceId shouldBe "lido:2026-06-15-sorry"
    }

    @Test
    fun `leaves the date to the overview because the detail header carries none`() {
        sorry.eventDate shouldBe UNRESOLVED_EVENT_DATE
    }

    @Test
    fun `parses the presenter as a promoter`() {
        sorry.promoters shouldContainExactly listOf("Puschen")
    }

    @Test
    fun `splits presale and box-office prices, tolerating the non-breaking space before the euro sign`() {
        // "25,00 €" Vorverkauf → presale; "30,00 €" Abendkasse → box office.
        sorry.pricePresale shouldBe BigDecimal("25.00")
        sorry.priceBoxOffice shouldBe BigDecimal("30.00")
    }

    @Test
    fun `parses the ticket shop URL and image`() {
        sorry.ticketUrl shouldContain "tixforgigs.com"
        sorry.imageUrl shouldContain "kulturhaeuser-production"
    }

    @Test
    fun `parses the artist biography into the description`() {
        sorry.description.shouldNotBeNull()
        sorry.description!! shouldContain "Who are Sorry"
    }

    @Test
    fun `leaves the artist roster to the overview page`() {
        sorry.artists.shouldBeEmpty()
    }

    @Test
    fun `returns null when the page has no event content`() {
        val doc = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(doc, sourceUrl).shouldBeNull()
    }

    @Test
    fun `keeps the shouted banner under the subtitle as the status note, which names where the show moved`() {
        val url = "https://www.lido-berlin.de/events/2026-11-10-sons-of-legion"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/lido/lido-detail-relocated.html")!!
                .bufferedReader()
                .readText()
        val sonsOfLegion = scraper.scrape(Jsoup.parse(html, url), url)!!

        sonsOfLegion.status shouldBe "RELOCATED"
        sonsOfLegion.subtitle shouldBe "„Soul to SØL World Tour 2026\""
        sonsOfLegion.statusNote shouldBe "HOCHVERLEGT IN DAS COLUMBIA THEATER. BEREITS GEKAUFTE TICKETS BEHALTEN IHRE GÜLTIGKEIT!"
        sonsOfLegion.toEventEntity(venueId = 1, venueSlug = "lido", eventSourceId = 1).relocatedTo shouldBe "Columbia Theater"
    }
}

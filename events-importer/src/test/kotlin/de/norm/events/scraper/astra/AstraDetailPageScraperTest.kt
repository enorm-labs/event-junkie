package de.norm.events.scraper.astra

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [AstraDetailPageScraper].
 *
 * Uses static HTML fixtures derived from real Astra detail pages for
 * deterministic, offline-safe testing without HTTP fetching.
 */
class AstraDetailPageScraperTest {
    private val scraper = AstraDetailPageScraper()

    private fun parseFixture(
        fixture: String,
        sourceUrl: String
    ): ScrapedEvent {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/astra/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    private val greenLung: ScrapedEvent by lazy {
        parseFixture("astra-detail-green-lung.html", "https://www.astra-berlin.de/events/2026-05-18-green-lung")
    }

    @Test
    fun `parses title, date and times from the shared event header`() {
        greenLung.title shouldBe "GREEN LUNG"
        greenLung.eventDate shouldBe LocalDate.of(2026, 12, 11)
        greenLung.doorsTime shouldBe LocalTime.of(18, 0)
        greenLung.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `derives sourceId from the URL slug, not the on-page date`() {
        // The slug keeps its original date (2026-05-18) even though the event was
        // rescheduled to December — the slug is the stable identity.
        greenLung.sourceId shouldBe "astra:2026-05-18-green-lung"
    }

    @Test
    fun `parses the local promoter`() {
        greenLung.promoters shouldContainExactly listOf("Landstreicher Konzerte")
    }

    @Test
    fun `parses presale price with a comma decimal separator`() {
        greenLung.pricePresale shouldBe BigDecimal("39.90")
        greenLung.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `parses the ticket shop URL`() {
        greenLung.ticketUrl shouldContain "eventim.de"
    }

    @Test
    fun `parses the artist biography into the description`() {
        greenLung.description!! shouldContain "Green Lung emerged from London"
    }

    @Test
    fun `leaves event type and artists to the overview page`() {
        // The detail page renders neither the kind label nor the artist roster.
        greenLung.eventType.shouldBeNull()
        greenLung.artists.shouldBeEmpty()
    }

    @Test
    fun `parses sold-out badge and dot-separated price`() {
        val chapo =
            parseFixture("astra-detail-chapo.html", "https://www.astra-berlin.de/events/2026-12-09-chapo102")
        chapo.soldOut shouldBe true
        chapo.pricePresale shouldBe BigDecimal("35.20")
        chapo.promoters shouldContainExactly listOf("Trinity Music")
    }

    @Test
    fun `splits presale and box-office prices and matches AK only as a standalone token`() {
        val url = "https://www.astra-berlin.de/events/2026-05-18-x"
        val html =
            """
            <main class="page-content"><article><header class="event">
            <h1 class="event__title"><a class="event__title-link" href="/events/2026-05-18-x">X</a></h1>
            </header><aside><div class="prices">
            <div class="price"><div class="price__value">25,00€</div><div class="price__label">VVK Paket</div></div>
            <div class="price"><div class="price__value">30,00€</div><div class="price__label">AK</div></div>
            </div></aside></article></main>
            """.trimIndent()
        val event = scraper.scrape(Jsoup.parse(html, url), url)!!

        // "VVK Paket" contains the letters "ak" but not as a standalone token → presale, not box office.
        event.pricePresale shouldBe BigDecimal("25.00")
        event.priceBoxOffice shouldBe BigDecimal("30.00")
    }

    @Test
    fun `keeps the relocation banner out of the subtitle and puts it first in the description`() {
        // Buzzcocks, 5 September 2026: tour name, support line, blank line, then the shouted banner (#1138).
        val buzzcocks = parseFixture("astra-detail-buzzcocks.html", "https://www.astra-berlin.de/events/2026-09-05-buzzcocks")

        buzzcocks.subtitle shouldBe "„Celebrating 50 Years Of Buzzcocks\" + Support: GYM TONIC"
        buzzcocks.description.shouldNotBeNull()
        buzzcocks.description shouldStartWith "VERLEGT INS LIDO. BEREITS GEKAUFTE TICKETS BEHALTEN IHRE GÜLTIGKEIT!"
    }

    @Test
    fun `returns null when the page has no event content`() {
        val doc = Jsoup.parse("<html><body><main></main></body></html>", "https://www.astra-berlin.de/events/x")
        scraper.scrape(doc, "https://www.astra-berlin.de/events/x").shouldBeNull()
    }
}

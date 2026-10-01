package de.norm.events.scraper.panke

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Price tests for [PankeProgrammePageScraper] (#2210).
 *
 * The fixture is two upcoming articles of the 2026-10-01 page: one prints `Tickets: 20€`, the other
 * labels presale and door figures in one line. The other price lines the venue has printed are
 * read through [parsePankePrice] directly, quoted from the older fixtures' past list.
 */
class PankeProgrammePageScraperPricesTest {
    private val scraper = PankeProgrammePageScraper()
    private val sourceUrl = "https://www.pankeculture.com/programme/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/panke/panke-programme-prices.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `reads a tickets line as the presale price`() {
        val ska = event("panke:17471")
        ska.pricePresale shouldBe BigDecimal("20")
        ska.priceBoxOffice.shouldBeNull()
        ska.priceNote.shouldBeNull()
    }

    @Test
    fun `reads labelled presale and door figures and keeps the other tiers in the note`() {
        val rage = event("panke:17478")
        rage.pricePresale shouldBe BigDecimal("18")
        rage.priceBoxOffice shouldBe BigDecimal("23")
        rage.priceNote!!.startsWith("Admission is free for transgender women.") shouldBe true
    }

    @Test
    fun `reads a single entry figure as the door price`() {
        parsePankePrice(listOf("ENTRY 10 EURO")) shouldBe PankePrice(boxOffice = BigDecimal("10"))
        parsePankePrice(listOf("Cost: 12 euro")) shouldBe PankePrice(boxOffice = BigDecimal("12"))
    }

    @Test
    fun `reads the lowest figure of a tier whose currency follows only the last`() {
        parsePankePrice(listOf("Entry: 10/15 euro")) shouldBe PankePrice(boxOffice = BigDecimal("10"), note = "Entry: 10/15 euro")
    }

    @Test
    fun `keeps a donation as a note and never as a price`() {
        parsePankePrice(listOf("Recommended donation: 15€")) shouldBe PankePrice(note = "Recommended donation: 15€")
    }

    @Test
    fun `ignores a figure on a line that opens with no price label`() {
        parsePankePrice(listOf("15€ (link in bio)")) shouldBe PankePrice()
    }

    @Test
    fun `takes the first price line of several`() {
        parsePankePrice(listOf("Doors 19:00", "Tickets: 20€", "ENTRY 10 EURO")).presale shouldBe BigDecimal("20")
    }
}

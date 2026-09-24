package de.norm.events.scraper.junctionbar

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [JunctionBarShopPageScraper], against two saved shop pages: Seven Ears Dive on sale
 * at 13,00 EUR, and Hieke Hoffmann sold out at 10,00 EUR.
 */
class JunctionBarShopPageScraperTest {
    private val scraper = JunctionBarShopPageScraper()

    private fun fixture(name: String): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/junctionbar/$name")!!
                .bufferedReader()
                .readText(),
            "https://junction-bar-shop.de/"
        )

    @Test
    fun `reads the price of a night on sale, not the header cart's zero`() {
        val offer = scraper.scrape(fixture("junctionbar-shop-priced.html"))

        offer.pricePresale shouldBe BigDecimal("13.00")
        offer.soldOut shouldBe false
    }

    @Test
    fun `reads the sold-out badge and keeps the price beside it`() {
        val offer = scraper.scrape(fixture("junctionbar-shop-sold-out.html"))

        offer.soldOut shouldBe true
        offer.pricePresale shouldBe BigDecimal("10.00")
    }

    @Test
    fun `a page without a product yields no price and no badge`() {
        val offer = scraper.scrape(Jsoup.parse("<html><body><p>Seite nicht gefunden</p></body></html>"))

        offer.pricePresale.shouldBeNull()
        offer.soldOut shouldBe false
    }

    @Test
    fun `applyTo fills price and sold-out state and leaves the rest of the night alone`() {
        val night =
            ScrapedEvent(
                title = "Hieke Hoffmann Solo & Band",
                eventDate = LocalDate.of(2026, 9, 24),
                sourceUrl = "https://www.junction-bar.de/program/09_2026/09_26.html",
                sourceId = "junction-bar:hieke-hoffmann",
                ticketUrl = "https://junction-bar-shop.de/hieke-hoffmann.html"
            )

        val merged = JunctionBarShopOffer(pricePresale = BigDecimal("10.00"), soldOut = true).applyTo(night)

        merged shouldBe night.copy(pricePresale = BigDecimal("10.00"), soldOut = true)
    }
}

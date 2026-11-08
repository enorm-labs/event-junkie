package de.norm.events.scraper.junctionbar

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [JunctionBarShopPageScraper], against two saved shop pages: Seven Ears Dive on sale
 * at 13,00 EUR, and Hieke Hoffmann sold out at 10,00 EUR.
 */
class JunctionBarShopPageScraperTest {
    private val scraper = JunctionBarShopPageScraper()

    private val shopUrl = "https://junction-bar-shop.de/"

    @Test
    fun `reads the price of a night on sale, not the header cart's zero`() {
        val offer = scraper.scrape(fixtureDocument("junctionbar/junctionbar-shop-priced.html", shopUrl))

        offer.pricePresale shouldBe BigDecimal("13.00")
        offer.soldOut shouldBe false
    }

    @Test
    fun `reads the sold-out badge and keeps the price beside it`() {
        val offer = scraper.scrape(fixtureDocument("junctionbar/junctionbar-shop-sold-out.html", shopUrl))

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

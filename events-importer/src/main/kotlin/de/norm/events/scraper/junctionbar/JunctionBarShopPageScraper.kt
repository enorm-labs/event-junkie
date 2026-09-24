package de.norm.events.scraper.junctionbar

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.parseEurCodePrice
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.math.BigDecimal

/**
 * Pure HTML parser for a live night's page in the venue's Gambio ticket shop (`junction-bar-shop.de/<slug>.html`).
 *
 * The programme page links each live night here but prints no price. The shop page prints the ticket
 * price as `13,00 EUR` in `.current-price-container`, and a sold-out night carries a `.ribbon-sold-out`
 * badge. Both selectors are scoped to `.product-info-details`, the product's own column: the page
 * header holds an empty cart (`0,00 EUR`), and the cross-selling listing below can badge other nights.
 *
 * The page's JSON-LD `offers` block is not used: the shop omits it for a sold-out night.
 */
class JunctionBarShopPageScraper {
    /** Parses the ticket price and the sold-out badge from one shop page. */
    fun scrape(document: Document): JunctionBarShopOffer =
        JunctionBarShopOffer(
            pricePresale = parseEurCodePrice(document.textAt("$PRODUCT_DETAILS .current-price-container")),
            soldOut = document.selectFirst("$PRODUCT_DETAILS .ribbon-sold-out") != null
        )

    private companion object {
        const val PRODUCT_DETAILS = ".product-info-details"
    }
}

/**
 * What a shop page adds to the programme's live night. Not a `ScrapedEvent`: the programme stays
 * authoritative for everything else, so the shop only fills these two fields.
 */
data class JunctionBarShopOffer(
    /** The online ticket price, kept for a sold-out night too. */
    val pricePresale: BigDecimal? = null,
    val soldOut: Boolean = false
) {
    /** Applies this offer to the [event] the programme page produced. */
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            pricePresale = event.pricePresale ?: pricePresale,
            soldOut = event.soldOut || soldOut
        )
}

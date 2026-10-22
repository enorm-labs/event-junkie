package de.norm.events.scraper.modus

import org.jsoup.nodes.Document

/**
 * Pure parser for the venue's own pretix shop at [TICKET_SHOP_HOST], the only place a Modus
 * night says it is sold out: the event page keeps its ticket button either way.
 *
 * Each ticket type is an `article.product-row` whose `.availability-box` carries a state class —
 * `available`, or `gone` beside a printed `AUSVERKAUFT`. Other states (`unavailable` before
 * presale, `reserved` while held in carts) are not a sold-out night.
 */
object ModusTicketShopScraper {
    const val TICKET_SHOP_HOST = "mb.ticketlodge.de"

    /** Whether every ticket type on the page is gone; `false` on a page that lists none. */
    fun soldOut(document: Document): Boolean {
        val boxes = document.select("article.product-row .availability-box")
        return boxes.isNotEmpty() && boxes.all { it.hasClass("gone") }
    }
}

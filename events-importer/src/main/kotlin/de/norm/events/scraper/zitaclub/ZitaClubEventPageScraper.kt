package de.norm.events.scraper.zitaclub

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document

/**
 * Reads the fields the feed lacks from an event's own page: the tags, which name the styles, the
 * ticket link and the one price beside it. Null when the page holds no Events Manager event.
 */
class ZitaClubEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val content = document.selectFirst(".em-event-single") ?: return null
        val tags = content.select(".eventtags a").mapNotNull { it.text().blankToNull() }
        return event.copy(
            genre = tags.joinToString(", ").blankToNull() ?: event.genre,
            pricePresale = parsePriceValue(content.textAt(".ak-preis")) ?: event.pricePresale,
            ticketUrl = content.hrefAt("a.tickets") ?: event.ticketUrl
        )
    }
}

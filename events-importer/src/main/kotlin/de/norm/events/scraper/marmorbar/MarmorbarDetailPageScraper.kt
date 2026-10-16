package de.norm.events.scraper.marmorbar

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.parseLabelledPrices
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.parseWixTicketPrice
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.wixParagraphs
import de.norm.events.scraper.wixTicketPriceRange
import org.jsoup.nodes.Document

/**
 * Parser for one Marmorbar event page (`/en/event-details/<slug>`), read for the full text and the ticket prices.
 *
 * The page's `tickets` carry each tier's own price, without the Wix fee the listing adds. A night sold only at the door
 * has no tickets and states its door price in the text (`TICKETS AT THE DOOR — €18`). The text is the rich-content
 * `longDescription`, of which the listing's teaser is the first paragraph.
 */
class MarmorbarDetailPageScraper {
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val state = WixEventsWarmupData.pageState(document, EventSource.MARMORBAR)
        val event = state?.let { WixEventsWarmupData.eventOf(it, EventSource.MARMORBAR) } ?: return null
        val prices = state.path("tickets").mapNotNull { parseWixTicketPrice(it.path("price")) }
        val description =
            wixParagraphs(event.path("longDescription"))
                .mapNotNull { it.blankToNull() }
                .joinToString("\n\n")
                .blankToNull()
        return ScrapedEvent(
            title = event.stringOrNull("title").orEmpty(),
            description = description,
            eventDate = parseWixSchedule(event.path("scheduling").path("config")).date ?: UNRESOLVED_EVENT_DATE,
            pricePresale = prices.minOrNull()?.takeIf { it.signum() > 0 },
            priceBoxOffice = parseLabelledPrices(description).boxOffice,
            priceNote = wixTicketPriceRange(prices),
            free = prices.isNotEmpty() && prices.all { it.signum() == 0 },
            sourceUrl = url,
            sourceId = "${EventSource.MARMORBAR.sourceIdPrefix}${event.stringOrNull("id").orEmpty()}"
        )
    }
}

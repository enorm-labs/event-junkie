package de.norm.events.scraper.zimmer16

import de.norm.events.scraper.PAY_WHAT_YOU_WANT
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.endOn
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.schemaSoldOut
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import org.jsoup.nodes.Document

/**
 * Pure parser for one event's YesTicket page, which the homepage card links: a schema.org `Event` with the
 * start, the end, the price and the status, and the full text in the first `div.spacer--C`. The JSON-LD text
 * is cut at about 300 characters. Tickets are reserved there and paid at the door, so the price is the box office's.
 * A €0 night whose text says "Eintritt frei, pay what you want" is not free ([PAY_WHAT_YOU_WANT]).
 * Its `image` is not read: `cdn.yesticket.org/robots.txt` disallows the picture script it points to (#2905).
 */
class Zimmer16EventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val node = document.jsonLdEvents().firstOrNull() ?: return null
        val start = node.schemaTime("startDate")
        val end = node.schemaTime("endDate")
        val price = node.schemaOffers().firstNotNullOfOrNull { it.stringOrNull("price")?.toBigDecimalOrNull() }
        val text =
            document
                .selectFirst("div.spacer--C")
                ?.select("p")
                ?.map { it.text().trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                ?.ifBlank { null }
        val description = text ?: node.stringOrNull("description") ?: event.description
        return event.copy(
            description = description,
            startTime = start ?: event.startTime,
            endDate = end?.let { endOn(event.eventDate, start, it) } ?: event.endDate,
            endTime = end ?: event.endTime,
            priceBoxOffice = price?.takeIf { it.signum() > 0 } ?: event.priceBoxOffice,
            free = price?.signum() == 0 && description?.let(PAY_WHAT_YOU_WANT::containsMatchIn) != true,
            soldOut = node.schemaSoldOut(),
            status = node.schemaStatus()
        )
    }
}

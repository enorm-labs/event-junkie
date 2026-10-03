package de.norm.events.scraper.mehringhof

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.schemaSoldOut
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import org.jsoup.nodes.Document

/**
 * Pure parser for a Mehringhof-Theater ticket page (`tickets.mehringhoftheater.de/produkte/<id>-…`),
 * read through its schema.org `Event` JSON-LD: the description, the image, the lowest offer price,
 * sold out and the status. The `name` repeats venue and date (`COMEDY FLASH, Mehringhof-Theater,
 * 03.10.2026`) and the `endDate` is the shop's 6 a.m. default, so the listing keeps the title and
 * neither end is read.
 */
class MehringhofTicketPageScraper {
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val event = document.jsonLdEvents().firstOrNull()
        val name = event?.stringOrNull("name") ?: return null
        return ScrapedEvent(
            title = name,
            description = htmlParagraphText(event.stringOrNull("description")),
            eventDate = UNRESOLVED_EVENT_DATE,
            startTime = event.schemaTime("startDate"),
            imageUrl = event.schemaImageUrl(),
            sourceUrl = sourceUrl,
            sourceId = "",
            pricePresale = event.schemaOffers().mapNotNull { it.stringOrNull("price")?.toBigDecimalOrNull() }.minOrNull(),
            soldOut = event.schemaSoldOut(),
            status = event.schemaStatus()
        )
    }
}

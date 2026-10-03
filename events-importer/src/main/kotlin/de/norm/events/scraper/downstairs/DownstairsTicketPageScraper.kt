package de.norm.events.scraper.downstairs

import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.stringOrNull
import org.jsoup.nodes.Document
import java.math.BigDecimal

/** What a ticket page adds to every performance of its show: the text, the image and the lowest price. */
data class DownstairsShow(
    val description: String?,
    val imageUrl: String?,
    val price: BigDecimal?
)

/**
 * Pure parser for a Downstairs ticket page, read through its schema.org `Event` JSON-LD. The
 * `name` repeats venue and date and the `endDate` is the shop's 6 a.m. default, so neither is read.
 */
class DownstairsTicketPageScraper {
    fun scrape(document: Document): DownstairsShow? =
        document.jsonLdEvents().firstOrNull()?.let { event ->
            DownstairsShow(
                description = htmlParagraphText(event.stringOrNull("description")),
                imageUrl = event.schemaImageUrl(),
                price = event.schemaOffers().mapNotNull { it.stringOrNull("price")?.toBigDecimalOrNull() }.minOrNull()
            )
        }
}

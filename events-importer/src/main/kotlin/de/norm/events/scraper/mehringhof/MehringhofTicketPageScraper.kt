package de.norm.events.scraper.mehringhof

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaOfferPrices
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
 * neither end is read. The shop opens most descriptions with its concessions note and a rule of
 * underscores, which [withoutShopNotes] drops (#5098).
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
            description = htmlParagraphText(event.stringOrNull("description"))?.withoutShopNotes(),
            eventDate = UNRESOLVED_EVENT_DATE,
            startTime = event.schemaTime("startDate"),
            imageUrl = event.schemaImageUrl(),
            sourceUrl = sourceUrl,
            sourceId = "",
            pricePresale = event.schemaOfferPrices().minOrNull(),
            soldOut = event.schemaSoldOut(),
            status = event.schemaStatus()
        )
    }
}

/** This text without the shop's concessions paragraph and its underscore rules, or `null` when nothing else is left. */
internal fun String.withoutShopNotes(): String? =
    split(PARAGRAPH_GAP)
        .filterNot { it.startsWith(CONCESSIONS_NOTE) || it.isUnderscoreRule() }
        .joinToString("\n\n")
        .ifBlank { null }

private fun String.isUnderscoreRule(): Boolean = isNotEmpty() && all { it == '_' }

private val PARAGRAPH_GAP = Regex("""\n\n""")

private const val CONCESSIONS_NOTE = "Der ermäßigte Ticketpreis gilt"

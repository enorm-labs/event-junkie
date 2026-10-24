package de.norm.events.scraper.speakeazy

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isBoxOfficeLabel
import de.norm.events.scraper.parseLabelledPrices
import org.jsoup.nodes.Document

/**
 * Reads a Speakeazy event page into its listing event: the blurb, the door price and the ticket shop.
 *
 * - **The admission is a text block of its own**, `Abendkasse 20€`. It is read as the door price and kept out of the blurb.
 * - **A blurb still to come reads `folgt`**, and is dropped.
 * - **The `Tickets` button links the venue's Eventim Light shop.**
 *
 * A page without an `article.eventitem` yields null, so the run keeps what the page stored last time.
 */
class SpeakeazyEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val article = document.selectFirst("article.eventitem") ?: return null
        val blocks = article.select(".sqs-html-content").mapNotNull { htmlParagraphText(it.html()) }
        val (priceBlocks, textBlocks) = blocks.partition { isBoxOfficeLabel(it) && it.length <= PRICE_BLOCK_MAX_LENGTH }
        val ticketUrl =
            article
                .select("a.sqs-block-button-element[href]")
                .firstOrNull { TICKETS.matches(it.text()) }
                ?.absUrl("href")
                ?.takeIf { it.startsWith("http") }
        return event.copy(
            description = textBlocks.filterNot { PLACEHOLDER.matches(it) }.joinToString("\n").ifEmpty { null },
            priceBoxOffice = priceBlocks.firstNotNullOfOrNull { parseLabelledPrices(it).boxOffice },
            ticketUrl = ticketUrl ?: event.ticketUrl
        )
    }

    private companion object {
        /** A price block is one line; a blurb that mentions the box office is longer. */
        const val PRICE_BLOCK_MAX_LENGTH = 60

        /** A blurb still to come: `folgt`. */
        val PLACEHOLDER = Regex("""(?i)\s*(?:text\s+)?folgt\.?\s*""")

        val TICKETS = Regex("""(?i)\s*tickets?\s*""")
    }
}

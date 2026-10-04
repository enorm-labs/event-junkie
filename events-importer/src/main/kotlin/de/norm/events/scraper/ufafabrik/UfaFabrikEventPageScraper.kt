package de.norm.events.scraper.ufafabrik

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Pure parser for one show page, `/veranstaltung/<node>/<slug>`: the blurb in the article's
 * `field--name-body`, one paragraph per line. A bare link, a photo credit and the house's line on
 * which vouchers it does not take are dropped; they are not about the show. A guest production that
 * sells through its own shop writes `Ticketlink: <url>` or `Zum Ticket-Vorverkauf: <url>` in the
 * blurb instead of the month row's "Karten kaufen" banner (#2626). That URL becomes the ticket link,
 * and a line holding only label and URL leaves the blurb. The month row owns every other field, and
 * its own ticket link wins over this one.
 */
class UfaFabrikEventPageScraper {
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val paragraphs = document.select("article .field--name-body p")
        val description = description(paragraphs)
        val ticketUrl = paragraphs.firstNotNullOfOrNull(::ticketUrl)
        if (description == null && ticketUrl == null) return null
        return ScrapedEvent(
            title = "",
            description = description,
            eventDate = UNRESOLVED_EVENT_DATE,
            sourceUrl = url,
            sourceId = "",
            ticketUrl = ticketUrl
        )
    }

    private fun description(paragraphs: List<Element>): String? =
        paragraphs
            .map { paragraph -> paragraph.textLines().filterNot { TICKET_LINE.containsMatchIn(it) }.joinToString("\n") }
            .filterNot { it.isEmpty() || NOT_ABOUT_THE_SHOW.containsMatchIn(it) }
            .joinToString("\n")
            .ifEmpty { null }

    /** The anchor right after a ticket label, else a URL written as text after one. */
    private fun ticketUrl(paragraph: Element): String? =
        paragraph
            .select("a[href]")
            .firstOrNull { it.previousSibling()?.labelText()?.let(TICKET_LABEL_BEFORE_LINK::containsMatchIn) == true }
            ?.absUrl("href")
            ?.ifEmpty { null }
            ?: paragraph.textLines().firstNotNullOfOrNull { TICKET_LINE.find(it)?.groupValues?.get(1) }

    private fun Node.labelText(): String? =
        when (this) {
            is TextNode -> text()
            is Element -> text()
            else -> null
        }

    private companion object {
        val NOT_ABOUT_THE_SHOW = Regex("""^(?:https?://\S+|fotos?\s*:.*)$|können nicht eingelöst werden""", RegexOption.IGNORE_CASE)
        const val TICKET_LABEL = """(?:zum\s+)?ticket(?:-?link|-?vorverkauf|s)?\s*:"""
        val TICKET_LINE = Regex("""^$TICKET_LABEL\s*(https?://\S+)$""", RegexOption.IGNORE_CASE)
        val TICKET_LABEL_BEFORE_LINK = Regex("""$TICKET_LABEL\s*$""", RegexOption.IGNORE_CASE)
    }
}

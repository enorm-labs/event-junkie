package de.norm.events.scraper.ausland

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parser for one ausland event page, `article.event`: the event text, and the times and price the text states.
 *
 * The text is every paragraph of the right column below the line-up, up to the venue's standard accessibility notes.
 * A concert night states `Doors open 19h | Concert+Talks 20h | Tickets (only at the door) from 10+ €`, and the listing's
 * one clock is then the doors time. The price is that `€` or "free entry" segment, kept as a note, because it is prose
 * (`from 10+ €`, `Donation suggestion: € 5,00 - € 20`). The date is the listing's.
 */
class AuslandEventPageScraper {
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val column = document.selectFirst("article.event .col50.right") ?: return null
        val title = column.textAt("h2") ?: return null
        val lines = paragraphs(column)
        val text = lines.joinToString("\n")
        return ScrapedEvent(
            title = title,
            description = text.blankToNull(),
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = labelledClock(text, DOORS_LABELS),
            startTime = labelledClock(text, START_LABELS_WITH_CONCERT),
            priceNote = lines.flatMap { it.split(" | ") }.map(String::trim).firstOrNull { it.length <= MAX_PRICE_NOTE && PRICE_CUE.containsMatchIn(it) },
            sourceUrl = sourceUrl,
            sourceId = auslandSourceId(sourceUrl)
        )
    }

    /** The event text alone, for the page in the second language. */
    fun description(document: Document): String? =
        document
            .selectFirst("article.event .col50.right")
            ?.let { paragraphs(it) }
            ?.joinToString("\n")
            .blankToNull()

    private fun paragraphs(column: Element): List<String> =
        column
            .select("p")
            .filterNot { it.hasClass("date") || it.hasClass("artistinfo") || it.closest(NOT_TEXT) != null }
            .map { it.textLines().joinToString("\n") }
            .takeWhile { !ACCESSIBILITY_NOTES.containsMatchIn(it) }
            .filterNot { it.isBlank() || LINE_UP_CHANGE_NOTE.containsMatchIn(it) }

    private companion object {
        /** "Concert+Talks 20h" and "Konzert+Gespräche 20 Uhr" are the start of a concert night. */
        const val START_LABELS_WITH_CONCERT = """$START_LABELS|(?:konzert|concert)(?:\s*\+\s*\p{L}+)?"""

        /** Pasted texts nest their paragraphs in `div`s; these blocks around them are the page's own furniture. */
        const val NOT_TEXT = ".supportedby, .sharebuttons, .news-list-category"

        /** A price line is short; a `€` in a longer paragraph is prose about something else, such as a test kit. */
        const val MAX_PRICE_NOTE = 80

        val PRICE_CUE = Regex("""€|\bfree entry\b|\beintritt frei\b""", RegexOption.IGNORE_CASE)

        /** The standard accessibility notes that close a concert night's text, and everything after them. */
        val ACCESSIBILITY_NOTES = Regex("""^(?:Accessibility|Barrierefreiheit|Zugänglichkeit)\s*:""", RegexOption.IGNORE_CASE)

        /** The German pages' standard "line-up subject to change" line. */
        val LINE_UP_CHANGE_NOTE = Regex("""^\*\*\s*Änderungen der Line-ups""")
    }
}

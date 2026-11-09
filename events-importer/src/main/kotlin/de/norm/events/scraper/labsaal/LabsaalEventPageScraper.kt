package de.norm.events.scraper.labsaal

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.hasFreeEntryPhrase
import org.jsoup.nodes.Document

/**
 * Reads the `Preise` line of an event's own page, which the feed leaves out. A free-entry line marks
 * the night free. A line with figures is kept as the note, as written: its tiers carry no label, so
 * none is stored as presale or box office. Null when the page holds no Modern Events Calendar event.
 */
class LabsaalEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val content = document.selectFirst(".mec-wrap") ?: return null
        val cost = content.selectFirst("dd.mec-events-event-cost")?.text().blankToNull() ?: return event
        return when {
            hasFreeEntryPhrase(cost) -> event.copy(free = true)
            FIGURE.containsMatchIn(cost) -> event.copy(priceNote = cost, free = false)
            else -> event
        }
    }

    private companion object {
        val FIGURE = Regex("""\d""")
    }
}

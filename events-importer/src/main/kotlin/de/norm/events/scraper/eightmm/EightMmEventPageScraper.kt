package de.norm.events.scraper.eightmm

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.labelledClock
import org.jsoup.nodes.Document

/**
 * Reads an 8MM event page into its listing event: the blurb, and the `Doors: 19:00` / `Show: 20:30` lines a concert
 * blurb ends with. The calendar's own time is a slot the venue rarely edits, so a stated show time replaces it.
 * A page without a blurb leaves the event as listed; the footer's `sqs-html-content` sits outside the `article`.
 */
class EightMmEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent {
        val description =
            htmlParagraphText(document.selectFirst("article.eventitem .eventitem-column-content .sqs-html-content")?.html())
                ?: return event
        return event.copy(
            description = description,
            doorsTime = labelledClock(description, DOORS_LABELS),
            startTime = labelledClock(description, START_LABELS) ?: event.startTime
        )
    }
}

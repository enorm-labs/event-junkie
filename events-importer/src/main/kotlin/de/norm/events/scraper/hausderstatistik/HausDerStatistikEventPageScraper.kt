package de.norm.events.scraper.hausderstatistik

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.labelledClock
import org.jsoup.nodes.Document

/**
 * Parser for a Haus der Statistik event page (`/event/<slug>/`): the excerpt and the paragraphs
 * below it as the description, a `Doors: 19:00 | Show: 20:00` line as the two times, the
 * `Ticket/Kostenbeteiligung` cell as the price note and the full-size picture. The listing's time
 * span opens at the doors, so a stated show time replaces its start.
 */
class HausDerStatistikEventPageScraper {
    fun scrape(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val content = document.selectFirst("article.event .entry-content .column.is-8") ?: return null
        val paragraphs =
            content
                .select(".entry-excerpt p, > p")
                .mapNotNull { it.text().blankToNull() }
                .distinct()
        val text = paragraphs.joinToString("\n\n").blankToNull()
        return event.copy(
            description = text ?: event.description,
            doorsTime = labelledClock(text, DOORS_LABELS),
            startTime = labelledClock(text, START_LABELS) ?: event.startTime,
            priceNote = document.sidebarCell("Ticket/Kostenbeteiligung"),
            imageUrl = content.imgSrcAt("figure img") ?: event.imageUrl
        )
    }

    /** The text of the sidebar paragraph that a bold [label] opens, without the label. */
    private fun Document.sidebarCell(label: String): String? =
        select("article.event .entry-content .column.is-4 > p")
            .firstOrNull { it.selectFirst("strong")?.text() == label }
            ?.let { paragraph -> paragraph.text().removePrefix(label).blankToNull() }
}

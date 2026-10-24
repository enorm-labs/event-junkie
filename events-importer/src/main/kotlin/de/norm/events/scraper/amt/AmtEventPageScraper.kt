package de.norm.events.scraper.amt

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document

/**
 * Adds the prose of an AMT `/event/<slug>` page to the night's description. The month page carries
 * only the DJ line, and many nights leave it empty, so the night's text is on its own page only.
 * The DJ line stays first. A page without prose keeps the night as the month page gave it.
 */
internal fun addAmtEventPageDescription(
    event: ScrapedEvent,
    document: Document
): ScrapedEvent {
    val prose = htmlParagraphText(document.selectFirst(".rich-text-block")?.html()) ?: return event
    return event.copy(description = listOfNotNull(event.description, prose).joinToString("\n\n"))
}

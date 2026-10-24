package de.norm.events.scraper.amt

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document

/**
 * Adds what an AMT `/event/<slug>` page holds beyond the month page: its prose, after the DJ line
 * in the description, and its poster. The month page carries only the DJ line, and many nights
 * leave it empty. The poster's `src` is the original file; its `srcset` lists only smaller copies.
 * A night without a poster shows Webflow's empty-binding placeholder, marked `w-dyn-bind-empty`.
 */
internal fun addAmtEventPageFields(
    event: ScrapedEvent,
    document: Document
): ScrapedEvent {
    val prose = htmlParagraphText(document.selectFirst(".rich-text-block")?.html())
    val poster = document.selectFirst("img.slika-event:not(.w-dyn-bind-empty)")?.absUrl("src")?.blankToNull()
    return event.copy(
        description = listOfNotNull(event.description, prose).joinToString("\n\n").blankToNull(),
        imageUrl = poster ?: event.imageUrl
    )
}

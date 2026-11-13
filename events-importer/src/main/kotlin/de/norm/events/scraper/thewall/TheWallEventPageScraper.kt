package de.norm.events.scraper.thewall

import de.norm.events.scraper.descriptionText
import org.jsoup.nodes.Document

/**
 * Pure parser for a The Wall Comedy Club event page (`/events/<slug>/`). The listing's JSON-LD gives the
 * description as one run of text; the page renders the same text from Markdown, a `<p>` per paragraph, so
 * this reads it with its paragraphs (#4778). `null` when the page has no such block.
 */
class TheWallEventPageScraper {
    fun description(document: Document): String? = document.selectFirst(DESCRIPTION)?.descriptionText()

    private companion object {
        const val DESCRIPTION = ".card-body .card-text.markdown"
    }
}

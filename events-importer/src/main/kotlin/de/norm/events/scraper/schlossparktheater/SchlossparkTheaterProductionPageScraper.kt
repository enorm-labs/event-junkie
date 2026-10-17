package de.norm.events.scraper.schlossparktheater

import de.norm.events.scraper.blankToNull
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for a Schlosspark Theater production page (`/produktionen/<slug>.html`), which
 * every performance of the production shares. The text is the run of `p` children of
 * `#mainColumn` up to its first `hr`; the press quotes, trailer and dates follow it.
 */
class SchlossparkTheaterProductionPageScraper {
    fun scrape(document: Document): String? {
        val column = document.selectFirst("#werkdetail #mainColumn") ?: return null
        return column
            .children()
            .takeWhile { it.tagName() != "hr" }
            .filter { it.tagName() == "p" }
            .mapNotNull { it.text().blankToNull() }
            .joinToString("\n\n")
            .blankToNull()
    }
}

package de.norm.events.scraper.renaissancetheater

import de.norm.events.scraper.blankToNull
import org.jsoup.nodes.Document

/** What a Renaissance-Theater production page adds to its performances: the text and the hero image. */
data class RenaissanceTheaterProduction(
    val description: String?,
    val imageUrl: String?
)

/**
 * Pure HTML parser for a Renaissance-Theater production page (`/produktion/<slug>/`), which every
 * performance of the production shares. The text is the paragraphs of the `div` blocks in the
 * `.rt-main` column, the folded `#extended` part included; the accordion after them holds the
 * trailer, the running time and the dates.
 */
class RenaissanceTheaterProductionPageScraper {
    fun scrape(document: Document): RenaissanceTheaterProduction? {
        val column = document.selectFirst("main .rt-grid:not(.rt-headline) > .rt-main:not(.rt-galerie)") ?: return null
        val description =
            column
                .children()
                .filter { it.tagName() == "div" && !it.hasClass("rt-accordion-container") }
                .flatMap { it.select("p") }
                .mapNotNull { it.text().blankToNull() }
                .joinToString("\n\n")
                .blankToNull()
        val image = document.selectFirst("main .rt-hero img[src]")?.absUrl("src")?.blankToNull()
        return RenaissanceTheaterProduction(description, image).takeIf { description != null || image != null }
    }
}

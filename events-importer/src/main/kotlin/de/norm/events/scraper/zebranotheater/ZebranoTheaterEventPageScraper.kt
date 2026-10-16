package de.norm.events.scraper.zebranotheater

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Parser for a Zebrano-Theater `programm/<yyyyMMdd>.shtml` page: the prose in `#spal2` as the
 * description and the `#bild` photo. The headings repeat the listing's billing, the `.tickets` box its
 * price, the `Mehr über … erfahren Sie hier.` lines link the performers' own sites, and `#back` the listing.
 */
class ZebranoTheaterEventPageScraper {
    fun scrape(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val column = document.selectFirst("#spal2") ?: return null
        val description = paragraphs(column).joinToString("\n\n").blankToNull()
        val imageUrl = document.selectFirst("#bild img[src]")?.absUrl("src")?.blankToNull()
        if (description == null && imageUrl == null) return null
        return event.copy(description = description ?: event.description, imageUrl = imageUrl ?: event.imageUrl)
    }

    /** The column's own text, a paragraph per blank line; a single `br` keeps the paragraph together. */
    private fun paragraphs(column: Element): List<String> {
        val text = StringBuilder()
        column.childNodes().forEach { node ->
            when {
                node is TextNode -> text.append(node.text())

                node !is Element || node.tagName() in SKIPPED_TAGS || node.selectFirst(
                    "h2"
                ) != null || node.hasClass("tickets") || node.id() == BACK_LINK -> Unit

                node.tagName() == "br" -> text.append('\n')

                else -> text.append(node.text())
            }
        }
        return text
            .split(PARAGRAPH_BREAK)
            .map { paragraph ->
                paragraph
                    .lines()
                    .map { it.trim() }
                    .filterNot { MORE_ABOUT.containsMatchIn(it) }
                    .joinToString(" ")
                    .replace(SPACES, " ")
                    .trim()
            }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val BACK_LINK = "back"
        val SKIPPED_TAGS = setOf("h2", "h3", "script", "style")
        val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
        val SPACES = Regex("""\s+""")
        val MORE_ABOUT = Regex("""^Mehr über .+ erfahren Sie""")
    }
}

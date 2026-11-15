package de.norm.events.scraper.parkaue

import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.htmlParagraphText
import org.jsoup.nodes.Document

/** What a Theater an der Parkaue production page adds to its dates. */
data class ParkaueProduction(
    val description: String?,
    val imageUrl: String?
)

/**
 * Pure parser for a production page, which every date of the production links with its own number. The text is every
 * non-empty innermost `.richtext` in the section of the first one: a lead line and the body on some pages, after an empty
 * block on others. The section's anchor is spelled three ways, so it is not the key. The image is the gallery's first
 * photo, in `data-image-url` (the `src` is a placeholder).
 */
class ParkaueProductionPageScraper {
    fun scrape(document: Document): ParkaueProduction =
        ParkaueProduction(
            description = description(document),
            imageUrl = document.selectFirst("main img.gallery__image[data-image-url]")?.absUrl("data-image-url")?.blankToNull()
        )

    private fun description(document: Document): String? {
        // Innermost: no other `.richtext` below it. Whether `select` counts the element itself depends on jsoup's query mode.
        val blocks = document.select("main .richtext").filter { block -> block.hasText() && block.select(".richtext").all { it === block } }
        val first = blocks.firstOrNull() ?: return null
        val section = first.closest("section")
        return blocks
            .filter { section == null || it.closest("section") == section }
            .ifEmpty { listOf(first) }
            .mapNotNull { htmlParagraphText(it.html()) }
            .joinToString("\n\n")
            .blankToNull()
    }
}

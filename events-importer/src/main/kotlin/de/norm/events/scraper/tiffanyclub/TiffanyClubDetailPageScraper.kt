package de.norm.events.scraper.tiffanyclub

import org.jsoup.nodes.Document

/**
 * Pure HTML parser for a Tiffany Club `/event/<slug>/` page, which adds one thing to the listing:
 * the blurb. It is the first text widget of the `single-post` template. The guest-list note
 * beside it is boilerplate, and the popups outside the template hold forms.
 */
class TiffanyClubDetailPageScraper {
    fun scrapeDescription(document: Document): String? =
        document
            .select("[data-elementor-type=single-post] .elementor-widget-text-editor")
            .map { widget -> widget.select("p").map { it.text().trim() }.filter { p -> p.any(Char::isLetterOrDigit) } }
            .firstOrNull { paragraphs -> paragraphs.isNotEmpty() && !paragraphs.first().startsWith(GUEST_LIST_NOTE) }
            ?.joinToString("\n")

    private companion object {
        const val GUEST_LIST_NOTE = "Hinweis zur Gästeliste"
    }
}

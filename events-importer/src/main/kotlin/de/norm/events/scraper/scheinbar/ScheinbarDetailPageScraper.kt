package de.norm.events.scraper.scheinbar

import org.jsoup.nodes.Document

/** What a Scheinbar programme page adds to its evenings: the text and the first photo. */
data class ScheinbarProgramme(
    val description: String?,
    val imageUrl: String?
)

/**
 * Pure HTML parser for a Scheinbar programme page (`/programm/<slug>/`), which one host's four Open
 * Stage nights share. The text is the article's paragraphs after the date line and before the
 * "Zur Übersicht" link; the photo is the gallery's first full-size image.
 */
class ScheinbarDetailPageScraper {
    fun scrape(document: Document): ScheinbarProgramme {
        val article = document.selectFirst("article.progFloat")
        val text =
            article
                ?.select("> p")
                ?.filterNot { it.hasClass("dates_top") || it.hasClass("uebersicht") }
                ?.map { it.text().trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        return ScheinbarProgramme(
            description = text.joinToString("\n").takeIf { it.isNotBlank() },
            imageUrl = document.selectFirst("aside .first_img a[href]")?.absUrl("href")?.takeIf { it.isNotEmpty() }
        )
    }
}

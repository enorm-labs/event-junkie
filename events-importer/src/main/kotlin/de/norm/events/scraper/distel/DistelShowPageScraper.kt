package de.norm.events.scraper.distel

import org.jsoup.nodes.Document

/** What a DISTEL show page adds to its performances: the text and the stage photo. */
data class DistelShow(
    val description: String?,
    val imageUrl: String?
)

/**
 * Pure HTML parser for a DISTEL show page (`/spielplan/event/<slug>/`). One page serves every
 * performance of the show, so it is read once per show. The text is the `ce_text` paragraphs and
 * the running time under them; the photo is the header image.
 */
class DistelShowPageScraper {
    fun scrape(document: Document): DistelShow {
        val reader = document.selectFirst(".mod_event_reader .event_full")
        val paragraphs =
            reader
                ?.select(".text .ce_text p, .text .times")
                ?.map { it.text().trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        return DistelShow(
            description = paragraphs.joinToString("\n").takeIf { it.isNotBlank() },
            imageUrl = reader?.selectFirst(".head img[src]")?.absUrl("src")?.takeIf { it.isNotEmpty() }
        )
    }
}

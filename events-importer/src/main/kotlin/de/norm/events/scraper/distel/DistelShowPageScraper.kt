package de.norm.events.scraper.distel

import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.splitSegmentOnConjunctions
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** What a DISTEL show page adds to its performances: the text, the stage photo and a guest show's cast. */
data class DistelShow(
    val description: String?,
    val imageUrl: String?,
    val lineup: List<String> = emptyList()
)

/**
 * Pure HTML parser for a DISTEL show page (`/spielplan/event/<slug>/`). One page serves every
 * performance of the show, so it is read once per show. The text is the `ce_text` paragraphs and
 * the running time under them; the photo is the header image. A guest show can name its cast in a
 * paragraph that opens with a bold `LINE UP` (#3673); the ensemble's shows name theirs only in prose.
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
            imageUrl = reader?.selectFirst(".head img[src]")?.absUrl("src")?.takeIf { it.isNotEmpty() },
            lineup = reader?.select(".text .ce_text p")?.firstNotNullOfOrNull(::lineupOf).orEmpty()
        )
    }

    /** `<p><strong>LINE UP</strong><br><strong>A, B & C</strong></p>` bills A, B and C. */
    private fun lineupOf(paragraph: Element): List<String>? {
        if (paragraph.selectFirst("strong")?.text()?.let(LINE_UP::matches) != true) return null
        return paragraph
            .text()
            .replaceFirst(LINE_UP_PREFIX, "")
            .split(',')
            .flatMap { splitSegmentOnConjunctions(it.trim()) }
            .filterNot(::isNonArtistName)
    }

    private companion object {
        val LINE_UP = Regex("""line\s?-?up:?""", RegexOption.IGNORE_CASE)
        val LINE_UP_PREFIX = Regex("""^\s*line\s?-?up:?\s*""", RegexOption.IGNORE_CASE)
    }
}

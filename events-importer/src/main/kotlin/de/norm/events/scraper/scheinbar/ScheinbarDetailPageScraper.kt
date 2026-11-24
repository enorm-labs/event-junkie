package de.norm.events.scraper.scheinbar

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document

/** What a Scheinbar programme page adds to its evenings: the text, the first photo and the acts it lists. */
data class ScheinbarProgramme(
    val description: String?,
    val imageUrl: String?,
    val artists: List<ScrapedArtist> = emptyList()
)

/**
 * Pure HTML parser for a Scheinbar programme page (`/programm/<slug>/`), which one host's four Open
 * Stage nights share. The text is the article's paragraphs after the date line and before the
 * "Zur Übersicht" link; the photo is the gallery's first full-size image.
 *
 * A guest show may list its acts after a `Mit dabei:` line, one `<name> | <discipline>` per line
 * (`Allison Wonder | Magie`). The names are read up to the first line without a `|`; a duo on one
 * line (`Madlen & Rang De`) stays one act. A host's guests may also stand in the prose,
 * `Horst Blue und seine Gäste Katharina Hoffmann, … und Mai Horlemann gemeinsam …` (event 15296).
 */
class ScheinbarDetailPageScraper {
    fun scrape(document: Document): ScheinbarProgramme {
        val paragraphs =
            document
                .selectFirst("article.progFloat")
                ?.select("> p")
                ?.filterNot { it.hasClass("dates_top") || it.hasClass("uebersicht") }
                .orEmpty()
        val text = paragraphs.map { it.text().trim() }.filter { it.isNotEmpty() }
        return ScheinbarProgramme(
            description = text.joinToString("\n").takeIf { it.isNotBlank() },
            imageUrl = document.selectFirst("aside .first_img a[href]")?.absUrl("href")?.takeIf { it.isNotEmpty() },
            artists = listedActs(paragraphs.flatMap { it.textLines() }).ifEmpty { guestsInProse(text.joinToString(" ")) }
        )
    }

    private fun listedActs(lines: List<String>): List<ScrapedArtist> =
        lines
            .dropWhile { !it.equals(LIST_INTRO, ignoreCase = true) }
            .drop(1)
            .asSequence()
            .map { ACT_LINE.find(it)?.groupValues?.get(1) }
            .takeWhile { it != null }
            .filterNotNull()
            .map { ScrapedArtist(name = it) }
            .toList()

    /** The names after `seine Gäste`, up to the first word that is neither a name's nor a join. */
    private fun guestsInProse(text: String): List<ScrapedArtist> =
        GUEST_LIST
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.split(GUEST_SEPARATOR)
            ?.map { ScrapedArtist(name = it) }
            .orEmpty()

    private companion object {
        private const val PERSON = """\p{Lu}[\p{L}'-]+(?:\s+(?:van|von|de|der|den)){0,2}(?:\s+\p{Lu}[\p{L}'-]+)+"""
        val GUEST_LIST = Regex("""\b(?:seine|ihre)\s+Gäste\s+($PERSON(?:(?:,\s*|\s+und\s+)$PERSON)*)""")
        val GUEST_SEPARATOR = Regex(""",\s*|\s+und\s+""")
        const val LIST_INTRO = "Mit dabei:"
        val ACT_LINE = Regex("""^(.+?)\s+\|\s+\S""")
    }
}

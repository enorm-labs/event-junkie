package de.norm.events.scraper.slaughterhouse

import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.stripArtistSuffix

/**
 * The acts and DJs a Slaughterhouse entry's own paragraphs name, read before its title (#4094). A title can open with a
 * series (`Aufnahme+Wiedergabe: …`) or be a night's name (`Kulturfabrik Winterfest`), so the lines are the better source.
 *
 * Headliners come from a paragraph that opens `Sutcliffe No More (UK) live`, a `– Konzert: a, b` sub-line, or a paragraph
 * of bare names. DJs come from `djs: a, b`, a bare `djs` or `on decks:` line over one name per line (`▷` bullets,
 * `+ special guest:` labels skipped), or a `– Party: DJ x` sub-line.
 */
internal data class SlaughterhouseLineup(
    val headliners: List<String>,
    val djs: List<String>
) {
    companion object {
        fun of(paragraphs: List<List<String>>): SlaughterhouseLineup {
            val lines = paragraphs.flatten()
            val headliners =
                paragraphs.mapNotNull(::liveAct) +
                    subBill(lines, "konzert") +
                    paragraphs.filter(::isNameBlock).flatten()
            val djs =
                lines.mapNotNull { DJS_INLINE.matchEntire(it)?.groupValues?.get(1) }.flatMap(::commaList) +
                    paragraphs.flatMap(::listedUnderDjHeader) +
                    subBill(lines, "party")
            return SlaughterhouseLineup(
                headliners = headliners.map { stripArtistSuffix(it) }.cleaned(),
                djs = djs.map { it.replace(DJ_PREFIX, "").replace(TRAILING_BRACKET, "") }.cleaned()
            )
        }

        private fun liveAct(paragraph: List<String>): String? =
            paragraph
                .firstOrNull()
                ?.let { LIVE_LINE.matchEntire(it) }
                ?.groupValues
                ?.get(1)

        private fun subBill(
            lines: List<String>,
            kind: String
        ): List<String> =
            lines
                .mapNotNull { SUB_BILL.matchEntire(it) }
                .filter { it.groupValues[1].equals(kind, ignoreCase = true) }
                .flatMap { commaList(it.groupValues[2]) }

        private fun listedUnderDjHeader(paragraph: List<String>): List<String> {
            val header = paragraph.indexOfFirst { DJ_HEADER.matches(it) }
            if (header < 0) return emptyList()
            return paragraph
                .drop(header + 1)
                .filterNot { it.trimEnd().endsWith(':') }
                .map { it.replace(BULLET, "") }
        }

        private fun isNameBlock(paragraph: List<String>): Boolean =
            paragraph.size >= 2 && paragraph.none { DJ_HEADER.matches(it) } &&
                paragraph.all { NAME_LINE.matches(it) && it.split(' ').size <= MAX_NAME_WORDS }

        private fun commaList(text: String): List<String> = text.split(',')

        private fun List<String>.cleaned(): List<String> =
            map { it.trim() }
                .filter { it.isNotEmpty() && !isNonArtistName(it) }
                .distinctBy { it.lowercase() }

        private const val MAX_NAME_WORDS = 4

        /** A band's blurb opens with its name and `live`: `Control (USA) live`. */
        private val LIVE_LINE = Regex("""^([^.!?:]{1,60}?)\s+live$""", RegexOption.IGNORE_CASE)

        /** A house-wide programme's line for this room: `– Konzert: Without Shrimps, WR51` or `– Party: DJ Mark Reeder`. */
        private val SUB_BILL = Regex("""^[–—-]?\s*(Konzert|Party)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val DJS_INLINE = Regex("""^djs\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val DJ_HEADER = Regex("""^(?:djs|on decks)\s*:?$""", RegexOption.IGNORE_CASE)
        private val BULLET = Regex("""^[▷•*+–—-]\s*""")
        private val DJ_PREFIX = Regex("""^djs?\s+""", RegexOption.IGNORE_CASE)

        /** A DJ's crew after the name: `paura diamante (eisengrau)`. */
        private val TRAILING_BRACKET = Regex("""\s*\([^()]*\)\s*$""")

        /** A bare name: no digit, and none of the punctuation a sentence, a field or a genre list carries. */
        private val NAME_LINE = Regex("""^\p{L}[^\d:!?.,;/·•|@()\[\]]{0,39}$""")
    }
}

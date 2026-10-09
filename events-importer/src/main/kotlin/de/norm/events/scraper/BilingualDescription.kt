package de.norm.events.scraper

import de.norm.events.event.DescriptionLanguage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level

private val logger = KotlinLogging.logger {}

/**
 * One description the venue wrote in German and in English, cut at its marker (ADR-026 rule 2,
 * #330). [original] is the half the venue put first, [alt] the other. A shared head (title lines, a
 * line-up) and a shared tail go into both.
 */
data class BilingualDescription(
    val original: String,
    val alt: String
)

/**
 * This event with its description cut in two where the venue wrote both languages into it, else
 * unchanged. [ScrapedEvent.toEventEntity] calls it for every source, so a scraper does not.
 */
fun ScrapedEvent.withBilingualDescriptionSplit(): ScrapedEvent {
    if (descriptionAlt != null) return this
    val split = splitBilingualDescription(description)
    if (split == null && hasLanguageMarker(description)) {
        logger.at(Level.DEBUG) {
            message = "Description of '$title' has a language marker but no German and English halves, stored whole"
            payload = mapOf(LogFields.EVENT_SOURCE_ID to sourceId)
        }
    }
    return split?.let { copy(description = it.original, descriptionAlt = it.alt) } ?: this
}

/** Whether [text] holds a line that names a language or points at one. */
fun hasLanguageMarker(text: String?): Boolean = !text.isNullOrBlank() && text.lines().any { POINTER.containsMatchIn(it) || headingLanguage(it) != null }

/**
 * [text] cut into its German and English halves, or null when it is not one text in both.
 *
 * The cut is at a heading on a line of its own (`EN`, `[EN]`, `English:`, `// ENGLISH VERSION`,
 * `Deutsch`) or a separator (`English version above`). A pointer (`(for English please scroll
 * down)`, `[English below]`, `(Deutsche Version unten)`) is removed. With a pointer and no heading,
 * the cut is the line where the stop-word detection switches language for good.
 *
 * Lines before the first half that [DescriptionLanguage] cannot call are a shared head. A trailing
 * run of at least [MIN_TAIL_LINES] such lines is a shared tail: the line-up after the second half.
 * A single closing line stays with its half. Each half must detect as the language it is cut as,
 * and the two must differ. Anything less returns null, so the text is stored as published.
 */
fun splitBilingualDescription(text: String?): BilingualDescription? {
    if (text.isNullOrBlank()) return null
    val raw = text.lines().map { it.trim() }
    val hasPointer = raw.any { POINTER.containsMatchIn(it) }
    val lines = raw.mapNotNull { line -> if (POINTER.containsMatchIn(line)) stripPointer(line) else line.ifEmpty { null } }
    val cut = byHeadings(lines) ?: if (hasPointer) byDetection(lines) else null
    return cut?.toBilingual()
}

/** The lines of one text, cut into a shared head and language sections. */
private data class Cut(
    val head: List<String>,
    val sections: List<Pair<DescriptionLanguage, List<String>>>
) {
    /** The two halves, or null unless exactly German and English are present and each reads as itself. */
    fun toBilingual(): BilingualDescription? {
        val nonEmpty = sections.filter { it.second.isNotEmpty() }
        val (body, tail) = splitTail(nonEmpty)
        val halves = body.groupBy({ it.first }, { it.second }).mapValues { (_, parts) -> parts.flatten() }
        val confirmed = halves.size == 2 && halves.all { (language, lines) -> detect(lines) == language }
        if (!confirmed) return null
        val (first, second) = halves.keys.toList()
        val compose = { language: DescriptionLanguage -> (head + halves.getValue(language) + tail).joinToString("\n") }
        return BilingualDescription(original = compose(first), alt = compose(second))
    }

    /** The trailing run of undetectable lines of the last section, when long enough to be a line-up. */
    private fun splitTail(sections: List<Pair<DescriptionLanguage, List<String>>>): Pair<List<Pair<DescriptionLanguage, List<String>>>, List<String>> {
        val last = sections.lastOrNull()
        val run = last?.second?.takeLastWhile { DescriptionLanguage.detect(it) == null }.orEmpty()
        return if (last != null && run.size >= MIN_TAIL_LINES && run.size < last.second.size) {
            sections.dropLast(1) + (last.first to last.second.dropLast(run.size)) to run
        } else {
            sections to emptyList()
        }
    }
}

/** The cut at headings and separators, or null when the text has none. */
private fun byHeadings(lines: List<String>): Cut? {
    val headings = lines.indices.mapNotNull { i -> headingLanguage(lines[i])?.let { i to it } }
    if (headings.isEmpty()) return null
    val preamble = lines.subList(0, headings.first().first)
    val sections =
        headings.mapIndexed { k, (index, language) ->
            val end = headings.getOrNull(k + 1)?.first ?: lines.size
            language to lines.subList(index + 1, end)
        }
    // Unmarked text before the first heading is the first half, after a shared head: title lines, a line-up.
    val lead = preamble.indices.firstOrNull { detect(preamble.subList(it, preamble.size)) != null } ?: preamble.size
    val firstHalf = preamble.subList(lead, preamble.size)
    val opening = detect(firstHalf)?.let { listOf(it to firstHalf) }.orEmpty()
    return Cut(preamble.subList(0, lead), opening + sections)
}

/** The cut where the per-line detection switches language once and does not switch back. */
private fun byDetection(lines: List<String>): Cut? {
    val languages = lines.map { DescriptionLanguage.detect(it)?.language }
    val start = languages.indexOfFirst { it != null }
    val firstLanguage = languages.getOrNull(start)
    val switch = languages.indexOfFirst { it != null && it != firstLanguage }
    val secondLanguage = languages.getOrNull(switch)
    val lastOfFirst = languages.indexOfLast { it == firstLanguage }
    if (firstLanguage == null || secondLanguage == null || lastOfFirst > switch) return null
    // The lines between the halves that no detection calls go with the half they lean to.
    val cut = (lastOfFirst + 1 until switch).firstOrNull { DescriptionLanguage.leaning(lines[it]) == secondLanguage } ?: switch
    return Cut(
        head = lines.subList(0, start),
        sections = listOf(firstLanguage to lines.subList(start, cut), secondLanguage to lines.subList(cut, lines.size))
    )
}

/** The language of the text that follows [line], when [line] is a heading or a separator. */
private fun headingLanguage(line: String): DescriptionLanguage? {
    val trimmed = line.trim()
    val heading = HEADING.matchEntire(trimmed)?.let { if (it.groups["en"] != null) DescriptionLanguage.ENGLISH else DescriptionLanguage.GERMAN }
    // "English version above" ends the English half, so German follows.
    val separator = ABOVE.matchEntire(trimmed)?.let { if (it.groups["en"] != null) DescriptionLanguage.GERMAN else DescriptionLanguage.ENGLISH }
    return heading ?: separator
}

/** [line] without its pointer and the dashes around it, or null when nothing else is on it. */
private fun stripPointer(line: String): String? = line.replace(POINTER, "").trim { it.isWhitespace() || it in DECORATION }.ifEmpty { null }

private fun detect(lines: List<String>): DescriptionLanguage? = DescriptionLanguage.detect(lines.joinToString("\n"))?.language

/** Characters a venue puts around a marker: brackets, dashes, slashes, stars, a colon. */
private const val DECORATION = "-–—_*/\\|.:[]()"

private const val DECORATION_RUN = """[\s\-–—_*/\\|.:\[\]()]*"""

/** A language named on a line of its own: `EN`, `[EN]`, `English:`, `// ENGLISH VERSION`, `— english version —`, `Deutsch`. */
private val HEADING =
    Regex(
        """$DECORATION_RUN(?:(?<en>en|eng|english|englisch)|(?<de>de|deu|deutsch|deutsche|german))(?:\s+version)?$DECORATION_RUN""",
        RegexOption.IGNORE_CASE
    )

/** A separator that names the half above it: `- - - English version above - - -`. */
private val ABOVE =
    Regex(
        """$DECORATION_RUN(?:(?<en>english|englische)|(?<de>german|deutsche))\s+version\s+(?:above|oben)$DECORATION_RUN""",
        RegexOption.IGNORE_CASE
    )

/** A note that the other language follows somewhere below, without marking where. */
private val POINTER =
    Regex(
        """[\[(]?\s*(?:for english,?\s+please\s+scroll\s+down|scroll\s+down\s+for\s+english|""" +
            """(?:english|german)(?:\s+version)?\s+below|deutsche\s+version\s+unten)\s*[\])]?""",
        RegexOption.IGNORE_CASE
    )

/** Fewer trailing lines than this are the second half's own sign-off, not a shared line-up. */
private const val MIN_TAIL_LINES = 2

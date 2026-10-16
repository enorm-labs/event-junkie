package de.norm.events.scraper.wabe

import de.norm.events.common.deshoutWord
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.splitSegmentOnConjunctions
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * The billing column of one WABE programme row. [printedTitle] is the row's first line, which keys
 * the `sourceId`; [title] differs from it only when that line names no show (`In Concert`).
 */
internal class WabeBilling(
    val printedTitle: String,
    val title: String,
    val subtitle: String?,
    val artists: List<ScrapedArtist>,
    val presenter: String?,
    private val priceLines: List<String>
) {
    val boxOffice: BigDecimal? get() = lowestAmount(BOX_OFFICE)
    val presale: BigDecimal? get() = lowestAmount(PRESALE)
    val free: Boolean get() = detectFree(presale, boxOffice, priceLines.joinToString(" ").ifEmpty { null })

    /** The cheapest tier of the line that names [label]: `15 € / 10 € (Abendkasse)` is a 10 € door. */
    private fun lowestAmount(label: Regex): BigDecimal? = priceLines.filter { label.containsMatchIn(it) }.flatMap(::euroAmounts).minOrNull()
}

/**
 * Reads the billing column: an optional `Veranstaltung des …` credit, the show's lines, and the
 * prices. Lines in capitals name performers, unless a label marks them as a director's credit.
 */
internal fun parseWabeBilling(column: Element): WabeBilling? {
    val modules = column.select(".j-text").map(::jimdoLines).filter { it.isNotEmpty() }
    val presenter = modules.firstNotNullOfOrNull { PRESENTER.matchEntire(it.joinToString(" ")) }?.let(::presenterName)
    val priceLines = modules.filter { lines -> lines.any { EURO.containsMatchIn(it) || FREE.containsMatchIn(it) } }.flatten()
    val main =
        modules.firstOrNull { lines -> PRESENTER.matchEntire(lines.joinToString(" ")) == null && lines.none { EURO.containsMatchIn(it) } }
            ?: return null
    val printedTitle = main.first()
    val rest = main.drop(1)
    val performerLines = rest.filter(::isPerformerLine)
    val otherLines = rest - performerLines.toSet()
    val generic = printedTitle.lowercase() in GENERIC_TITLES && performerLines.isNotEmpty()
    val title = if (generic) deshout(performerLines.first().substringAfter(':').trim()) else printedTitle
    val shownLines = (otherLines + performerLines).map(::deshoutNames)
    val subtitleLines = if (generic) listOf(printedTitle) + shownLines.filterNot { it == title } else shownLines
    return WabeBilling(
        printedTitle = printedTitle,
        title = title,
        subtitle = subtitleLines.joinToString(" · ").ifEmpty { null },
        artists = performerLines.flatMap(::actsOf).distinctBy { it.name.lowercase() },
        presenter = presenter,
        priceLines = priceLines
    )
}

/**
 * The element's text as display lines: a break at every `<br>` and every paragraph, the rest of the
 * whitespace collapsed. Jimdo nests its breaks inside spans, which `textLines` does not see.
 */
internal fun jimdoLines(element: Element): List<String> {
    val copy = element.clone()
    copy.select("br").forEach { it.after(LINE_MARK) }
    copy.select("p, div").forEach { it.appendText(LINE_MARK) }
    return copy
        .text()
        .split(LINE_MARK)
        .map { it.replace(' ', ' ').trim() }
        .filter { it.isNotEmpty() }
}

/** A line naming performers: shouted, optionally after a lowercase label such as `am Klavier:` or `Special Guest:`. */
private fun isPerformerLine(line: String): Boolean {
    val label = line.substringBefore(':', "")
    if (CREDIT_LABEL.containsMatchIn(label)) return false
    val names = line.substringAfter(':').trim()
    return names.any { it.isLetter() } && names.none { it.isLowerCase() }
}

/**
 * The acts a performer line bills. A pair splits only when both sides are full names, so a duo
 * billed by first names (`KAEY & JOSÉ`) stays one act. The ensemble a leader brings (`& BAND`,
 * `& GÄSTE`) is not an act.
 */
private fun actsOf(line: String): List<ScrapedArtist> {
    val guest = GUEST_LABEL.containsMatchIn(line.substringBefore(':', ""))
    val names = deshout(line.substringAfter(':').trim()).replace(ENSEMBLE_TAIL, "").trim()
    val parts = splitSegmentOnConjunctions(names).map { it.trim() }
    val acts = if (parts.all { ' ' in it }) parts else listOf(names)
    return acts
        .filterNot { it.isEmpty() || isNonArtistName(it) }
        .map { ScrapedArtist(name = it, role = if (guest) "SUPPORT" else "HEADLINER") }
}

/** [line] with its shouted names title-cased, the label before a colon kept as printed. */
private fun deshoutNames(line: String): String {
    val label = line.substringBefore(':', "")
    if (!isPerformerLine(line) && !CREDIT_LABEL.containsMatchIn(label)) return line
    val names = deshout(line.substringAfter(':').trim())
    return if (label.isEmpty()) names else "$label: $names"
}

/** `Theaters unterm Dach` in the genitive credit is the `Theater unterm Dach`. */
private fun presenterName(match: MatchResult): String {
    val name = match.groupValues[2].trim().trimEnd('.')
    return if (match.groupValues[1].equals("des", ignoreCase = true)) name.replaceFirst(GENITIVE_S, "$1") else name
}

private fun deshout(name: String): String = name.trim().split(' ').joinToString(" ") { it.deshoutWord() }

private const val LINE_MARK = "¶"
private val PRESENTER = Regex("""(?:eine\s+)?veranstaltung\s+(des|der|von)\s+(.+)""", RegexOption.IGNORE_CASE)
private val GENITIVE_S = Regex("""^(\p{L}+)s\b""")
private val EURO = Regex("""\d\s*€|€\s*\d""")
private val FREE = Regex("""\beintritt\s+frei\b""", RegexOption.IGNORE_CASE)
private val BOX_OFFICE = Regex("""abendkasse""", RegexOption.IGNORE_CASE)
private val PRESALE = Regex("""vorverkauf""", RegexOption.IGNORE_CASE)

/** A credit that names a director or author, not a performer: `Text und Regie: TJANA THIESSENHUSEN`. */
private val CREDIT_LABEL = Regex("""regie|text|autor|buch|inszenierung""", RegexOption.IGNORE_CASE)
private val GUEST_LABEL = Regex("""guest|gast""", RegexOption.IGNORE_CASE)

/** The first line of a concert's billing that names no show, so the performers make the title. */
private val GENERIC_TITLES = setOf("in concert", "konzert", "live", "record-release-konzert")

/** The ensemble a billing appends to its leader (`LAURA GRANDY & BAND`), not an act of its own. */
private val ENSEMBLE_TAIL = Regex("""\s*(?:&|und|and)\s+(?:band|friends|freunde|gäste|guests)$""", RegexOption.IGNORE_CASE)

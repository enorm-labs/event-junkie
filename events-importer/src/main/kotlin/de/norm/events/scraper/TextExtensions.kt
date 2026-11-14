package de.norm.events.scraper

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

// The string readers every scraper needs, whatever markup or payload it reads. The DOM helpers
// are in ScrapingExtensions.

/**
 * Trims this string and returns `null` when it is null, empty, or all whitespace.
 *
 * An API that omits a field and one that sends `""` both mean "absent", and only `null` reaches
 * [ScrapedEvent] as one.
 */
fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * Decodes the HTML entities a CMS writes into the JSON and script blocks it renders.
 *
 * Jsoup hands script content back as raw text, so `&amp;` otherwise survives into the title, the
 * title-derived headliner and both slugs (`scala-amp-kolacny-brothers`).
 */
fun decodeHtmlEntities(raw: String): String = Parser.unescapeEntities(raw.trim(), false).trim()

/**
 * `&` when this URL already carries a query string, `?` when it does not.
 *
 * An importer appends its parsing parameters to an entry-point URL that the event source
 * configures either way (ADR-007).
 */
fun String.querySeparator(): Char = if ('?' in this) '&' else '?'

/** A run of whitespace, for splitting a line into words or flattening one to single spaces. */
val WHITESPACE = Regex("""\s+""")

/**
 * Flattens every run of whitespace in this string to one space, and trims both ends.
 *
 * Jsoup's `text()` and `ownText()` already do this. A string from anywhere else — `wholeText()`,
 * a `<br>`-split line, a JSON field — can still carry the source's line breaks.
 */
fun String.collapseWhitespace(): String = replace(WHITESPACE, " ").trim()

/** Length of the leading `HH:mm` a venue prefixes to a longer clock string (`HH:mm:ss`). */
const val HH_MM_LENGTH = 5

/**
 * Flattens an HTML body into plain text with the source's paragraphs and lines kept, the shape
 * [descriptionText] gives a DOM element. A paragraph ends with a blank line: a closing `</p>`,
 * heading, list, table or quote, a `<br>` after another one or after a `</div>`, and in a body
 * with no tags a blank line. Every other `<br>`, `</div>` and newline ends a line. `null` for a
 * missing or empty body. For a JSON payload that carries its text as HTML (Elfsight, radar).
 */
fun htmlParagraphText(html: String?): String? {
    val raw = html.blankToNull() ?: return null
    val plain = !ANY_TAG.containsMatchIn(raw)
    val marked = raw.replace(PARAGRAPH_END_PATTERN, "\n$PARAGRAPH_END\n").replace(BLOCK_BREAK_PATTERN, "\n")
    val out = StringBuilder()
    var gap = ""
    Jsoup.parse(marked).wholeText().lines().map { it.trim() }.forEach { line ->
        if (line == PARAGRAPH_END || (plain && line.isEmpty())) {
            gap = "\n\n"
        } else if (line.isNotEmpty()) {
            if (out.isNotEmpty()) out.append(gap.ifEmpty { "\n" })
            out.append(line)
            gap = ""
        }
    }
    return out.toString().blankToNull()
}

/** A `<br>` that follows another one or a closing `</div>` renders as a blank line, as a closing paragraph-level block does. */
private val PARAGRAPH_END_PATTERN =
    Regex("""(?i)</(?:p|h[1-6]|blockquote|ul|ol|table|section|article)>|(?:<br\s*/?>|</div>)\s*<br\s*/?>""")

private val ANY_TAG = Regex("""<[a-zA-Z/][^>]*>""")

/** A private-use character, so the paragraph mark survives the tag stripping. */
private const val PARAGRAPH_END = "\uE002"

private val BLOCK_BREAK_PATTERN = Regex("""(?i)<br\s*/?>|</(?:div|li|tr)>""")

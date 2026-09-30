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

/** Length of the leading `HH:mm` a venue prefixes to a longer clock string (`HH:mm:ss`). */
const val HH_MM_LENGTH = 5

/**
 * Flattens an HTML body into plain text, one paragraph per line: `<br>` and closing block tags
 * become newlines before the remaining tags are stripped, then blank lines are collapsed. `null`
 * for a missing or empty body. For a JSON payload that carries its text as HTML (Elfsight, radar).
 */
fun htmlParagraphText(html: String?): String? {
    val raw = html.blankToNull() ?: return null
    val withBreaks = raw.replace(BLOCK_BREAK_PATTERN, "\n")
    return Jsoup
        .parse(withBreaks)
        .wholeText()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .blankToNull()
}

private val BLOCK_BREAK_PATTERN = Regex("""(?i)<br\s*/?>|</div>|</p>""")

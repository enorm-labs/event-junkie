package de.norm.events.scraper.wuehlmaeuse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What a Wühlmäuse production page adds to its performances: the show text, null when the page has none yet, and the
 * guest lines it dates (`Gäste am 09.11.: …`), which belong to one performance each.
 */
data class WuehlmaeuseProduction(
    val description: String?,
    val guests: List<GuestLine> = emptyList()
)

/** One `Gäste am <day>.<month>.: a, b` line, as the page writes it, with its names. */
data class GuestLine(
    val day: Int,
    val month: Int,
    val names: List<String>,
    val line: String
)

/**
 * The guest line of the performance on [eventDate], or null. The line names no year, so it counts only for a performance
 * at most [GUEST_LINE_HORIZON_DAYS] days after [today]: a list for this November's show is not next November's (#4898).
 */
fun guestLineFor(
    eventDate: LocalDate,
    guests: List<GuestLine>,
    today: LocalDate
): GuestLine? {
    val daysAhead = ChronoUnit.DAYS.between(today, eventDate)
    if (daysAhead !in 0..GUEST_LINE_HORIZON_DAYS) return null
    return guests.firstOrNull { it.day == eventDate.dayOfMonth && it.month == eventDate.monthValue }
}

/** How far ahead a year-less guest line may point. */
const val GUEST_LINE_HORIZON_DAYS = 60L

/**
 * Pure HTML parser for a Wühlmäuse production page (`/veranstaltung/<slug>/`), which every
 * performance of one production links.
 *
 * The show text sits in an Elementor heading widget, after an empty `<h5>`:
 * `<p class="elementor-heading-title"><h5 class="elementor-heading-title"></h5>David Rauterberg … <br /> …`.
 * Jsoup closes the `<p>` at the `<h5>`, so the text is loose nodes beside it, up to the next
 * Elementor section. Lines break at `<br>`; a line that is only a link (`Homepage Rudelsingen`)
 * is dropped. A production not yet described says `Infos folgen…`, which is no text.
 *
 * A page without the widget parses to null, so its performances keep what was stored. A dated guest line leaves the text
 * and goes to [WuehlmaeuseProduction.guests], so a performance shows only its own ([guestLineFor]).
 */
class WuehlmaeuseProductionPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(document: Document): WuehlmaeuseProduction? {
        val heading = document.selectFirst(".elementor-widget-heading h5.elementor-heading-title")
        if (heading == null) {
            logger.warn { "Wühlmäuse production page has no show-text widget, keeping the stored text" }
            return null
        }
        val (guestLines, prose) = textLinesAfter(heading).partition { GUEST_LINE.matches(it) }
        val text = prose.joinToString("\n").takeUnless { it.isBlank() || PLACEHOLDER.matches(it) }
        return WuehlmaeuseProduction(text, guestLines.mapNotNull(::guestLine))
    }

    private fun guestLine(line: String): GuestLine? {
        val match = GUEST_LINE.matchEntire(line) ?: return null
        val (day, month, names) = match.destructured
        val split = names.split(NAME_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        return GuestLine(day.toInt(), month.toInt(), split, line)
    }

    /** The `<br>`-split lines of the inline nodes after [heading], up to the first block element. */
    private fun textLinesAfter(heading: Element): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var onlyLinks = true
        val flush = {
            val line = current.toString().trim()
            if (line.isNotEmpty() && !onlyLinks) lines.add(line)
            current.clear()
            onlyLinks = true
        }
        var node = heading.nextSibling()
        while (node != null) {
            when {
                node is Element && node.tagName() == "br" -> {
                    flush()
                }

                node is Element && node.isBlock -> {
                    break
                }

                node is Element -> {
                    current.append(node.text())
                    if (node.tagName() != "a" && node.text().isNotBlank()) onlyLinks = false
                }

                node is TextNode -> {
                    current.append(node.text())
                    if (node.text().isNotBlank()) onlyLinks = false
                }
            }
            node = node.nextSibling()
        }
        flush()
        return lines
    }

    private companion object {
        val PLACEHOLDER = Regex("""(?i)(weitere\s+)?infos?\s+folgen\W*""")

        /** `Gäste am 09.11.: Ben Schafmeister, Ilkan Ates`; the year is never written. */
        val GUEST_LINE = Regex("""^Gäste\s+am\s+(\d{1,2})\.(\d{1,2})\.?\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

        val NAME_SEPARATOR = Regex(""",|\s+und\s+|\s+&\s+""")
    }
}

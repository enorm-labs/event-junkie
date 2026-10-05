package de.norm.events.scraper.zigzag

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.splitBilingualDescription
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalTime

/** What an event page adds to its listing row. */
data class ZigZagDetail(
    val description: String?,
    /** The second-language half of a blurb the page writes in both languages. */
    val descriptionAlt: String? = null,
    val startTime: LocalTime?,
    val doorsTime: LocalTime?,
    val price: BigDecimal?,
    val ticketUrl: String?,
    val elsewhere: Boolean,
    val artists: List<ScrapedArtist> = emptyList()
)

/**
 * Pure parser for one Zig Zag event page, `/program-mai/<slug>`.
 *
 * The body is the line-up, the blurb, then a block opened by a "Tickets:" paragraph:
 * "Beginn: 20:00 Uhr (Einlass ab 19:00 Uhr)", "Eintritt: 25€", "Mitglieder: 12,50€" and a
 * "Location:" line. Times and prices are read from that block only, so a blurb's "show" or "€" is
 * never one. The JSON-LD `startDate` is the doors time, so it is not read. A two-show night prints
 * two "Beginn" lines, and the first is kept. A hall page names its location only in an image, so
 * [ZigZagDetail.elsewhere] means something on a club page only.
 *
 * The line-up comes before the "(for English please scroll down)" line, one musician a line:
 * `Name - instrument (country)` in `h3` or `p` elements, or in one `h3` broken by `<br>`. The dash
 * can be an en dash or lack the space before it, and the country can be missing. Each name is a
 * headliner, because a band has no support act; the instrument has no field to go to.
 */
class ZigZagDetailPageScraper {
    fun scrape(document: Document): ZigZagDetail {
        val paragraphs = document.select(".eventitem-column-content p")
        val ticketsAt = paragraphs.indexOfFirst { TICKETS_HEADING.matches(it.text().trim()) }
        val blurb = if (ticketsAt >= 0) paragraphs.take(ticketsAt) else paragraphs.toList()
        val tickets = if (ticketsAt >= 0) paragraphs.drop(ticketsAt).joinToString(" ") { it.text() } else ""
        // The pointer line stays in until the split, which needs it to know the text holds both languages.
        val bilingual = splitBilingualDescription(blurb.map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
        return ZigZagDetail(
            description = bilingual?.original ?: blurb.texts().joinToString("\n").ifEmpty { null },
            descriptionAlt = bilingual?.alt,
            startTime = labelledClock(tickets, START_LABELS),
            doorsTime = labelledClock(tickets, DOORS_LABELS),
            price = ADMISSION.find(tickets)?.let { euroAmounts(it.value).firstOrNull() },
            ticketUrl = document.selectFirst(".eventitem-column-content a[href*=eventim]")?.absUrl("href")?.ifEmpty { null },
            elsewhere = LOCATION.find(tickets)?.let { HOME_ADDRESS !in it.value.lowercase() } ?: false,
            artists = lineup(document)
        )
    }

    private fun lineup(document: Document): List<ScrapedArtist> =
        document
            .select(".eventitem-column-content h3, .eventitem-column-content p")
            .takeWhile { it.text().trim().let { text -> !ENGLISH_BELOW.matches(text) && !TICKETS_HEADING.matches(text) } }
            .flatMap { it.lines() }
            .mapNotNull {
                LINEUP_LINE
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }.filter { it.split(' ').size <= MAX_NAME_WORDS }
            .distinct()
            .map { ScrapedArtist(name = it, role = "HEADLINER") }

    /** The `<br>`-separated lines, a `<br>` inside `<strong>` included. */
    private fun Element.lines(): List<String> =
        clone()
            .apply { select("br").after("\n") }
            .wholeText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun List<Element>.texts(): List<String> = map { it.text().trim() }.filter { it.isNotEmpty() && !ENGLISH_BELOW.matches(it) }

    private companion object {
        const val HOME_ADDRESS = "hauptstr"

        val TICKETS_HEADING = Regex("""tickets\s*:""", RegexOption.IGNORE_CASE)
        val ENGLISH_BELOW = Regex("""\(for english please scroll down\)""", RegexOption.IGNORE_CASE)

        /** The full admission, which the members' rate after it never precedes. */
        val ADMISSION = Regex("""eintritt\s*:?\s*\d+(?:,\d{2})?\s*€""", RegexOption.IGNORE_CASE)
        val LOCATION = Regex("""location[^:]*:.{0,120}""", RegexOption.IGNORE_CASE)

        /** `Mette Nadja Hansen - Vocals (DK)`, `JOHANN GIESECKE- Trombone`; a comma in the name or a full stop means a blurb line. */
        val LINEUP_LINE = Regex("""(\p{L}[^,.!?:;()]{1,60}?)\s*[-–]\s+\p{L}[^.!?:;()]{0,40}?(?:\s*\(\p{L}{2,3}\))?""")
        const val MAX_NAME_WORDS = 5
    }
}

package de.norm.events.scraper.zigzag

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.labelledClock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalTime

/** What an event page adds to its listing row. */
data class ZigZagJazzClubDetail(
    val description: String?,
    val startTime: LocalTime?,
    val doorsTime: LocalTime?,
    val price: BigDecimal?,
    val ticketUrl: String?,
    val elsewhere: Boolean
)

/**
 * Pure parser for one Zig Zag event page, `/program-mai/<slug>`.
 *
 * The body is the line-up as `h3` lines, the blurb, then a block opened by a "Tickets:" paragraph:
 * "Beginn: 20:00 Uhr (Einlass ab 19:00 Uhr)", "Eintritt: 25€", "Mitglieder: 12,50€" and a
 * "Location:" line. Times and prices are read from that block only, so a blurb's "show" or "€" is
 * never one. The JSON-LD `startDate` is the doors time, so it is not read. A two-show night prints
 * two "Beginn" lines, and the first is kept.
 */
class ZigZagJazzClubDetailPageScraper {
    fun scrape(document: Document): ZigZagJazzClubDetail {
        val paragraphs = document.select(".eventitem-column-content p")
        val ticketsAt = paragraphs.indexOfFirst { TICKETS_HEADING.matches(it.text().trim()) }
        val blurb = if (ticketsAt >= 0) paragraphs.take(ticketsAt) else paragraphs.toList()
        val tickets = if (ticketsAt >= 0) paragraphs.drop(ticketsAt).joinToString(" ") { it.text() } else ""
        return ZigZagJazzClubDetail(
            description = blurb.texts().joinToString("\n").ifEmpty { null },
            startTime = labelledClock(tickets, START_LABELS),
            doorsTime = labelledClock(tickets, DOORS_LABELS),
            price = ADMISSION.find(tickets)?.let { euroAmounts(it.value).firstOrNull() },
            ticketUrl = document.selectFirst(".eventitem-column-content a[href*=eventim]")?.absUrl("href")?.ifEmpty { null },
            elsewhere = LOCATION.find(tickets)?.let { HOME_ADDRESS !in it.value.lowercase() } ?: false
        )
    }

    private fun List<Element>.texts(): List<String> = map { it.text().trim() }.filter { it.isNotEmpty() && !ENGLISH_BELOW.matches(it) }

    private companion object {
        const val HOME_ADDRESS = "hauptstr"

        val TICKETS_HEADING = Regex("""tickets\s*:""", RegexOption.IGNORE_CASE)
        val ENGLISH_BELOW = Regex("""\(for english please scroll down\)""", RegexOption.IGNORE_CASE)

        /** The full admission, which the members' rate after it never precedes. */
        val ADMISSION = Regex("""eintritt\s*:?\s*\d+(?:,\d{2})?\s*€""", RegexOption.IGNORE_CASE)
        val LOCATION = Regex("""location[^:]*:.{0,120}""", RegexOption.IGNORE_CASE)
    }
}

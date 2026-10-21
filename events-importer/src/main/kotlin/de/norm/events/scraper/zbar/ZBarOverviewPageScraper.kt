package de.norm.events.scraper.zbar

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClock
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Pure parser for Z Bar's hand-typed WordPress page. Each night opens with a date line in the
 * secondary colour (`p.has-secondary-background-color` with Latin text), and the blocks up to the next
 * highlighted paragraph belong to it: an `h6` title, a `Veranstalter: …` credit, the description, a
 * flyer and a ticket link. A month header or a "nach oben" link is highlighted in another colour.
 *
 * The date line is typed in the host's language (`Viernes, 13.11.2026, 19:30 hrs`), so only its
 * digits are read. One line can carry two nights (`Do. 29.10. + Do.26.11.2026`), and a date
 * without a year takes the line's year. A line with no `dd.MM` date (`Every Sunday`, `30 Ekim
 * Cuma`) is skipped. A night with no heading takes its title from its Eventbrite event link, and
 * one with neither is skipped, since its name is only on the flyer. An Arabic line beside the
 * Latin one repeats the date and has no Latin letter, so it opens no night.
 */
class ZBarOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val dateLines = document.select(".entry-content p.has-secondary-background-color").filter { LATIN.containsMatchIn(it.text()) }
        logger.info { "Found ${dateLines.size} date line(s) on the Z Bar page" }
        return dateLines.mapSkippingFailures(logger, "a Z Bar night") { parseNight(it, sourceUrl) }.flatten()
    }

    private fun parseNight(
        dateLine: Element,
        sourceUrl: String
    ): List<ScrapedEvent>? {
        val line = dateLine.text()
        val dates = datesIn(line)
        if (dates.isEmpty()) {
            logger.warn { "Z Bar date line '$line' names no date, skipping" }
            return null
        }
        val blocks = dateLine.nextElementSiblings().takeWhile { !it.opensSection() }
        val ticketUrl = blocks.firstNotNullOfOrNull(::ticketLink)
        val title = blocks.firstNotNullOfOrNull { it.selectFirst("h1, h2, h3, h4, h5, h6")?.text()?.blankToNull() } ?: ticketUrl?.let(::titleFromEventbrite)
        if (title == null) {
            logger.warn { "Z Bar night '$line' has no title, skipping" }
            return null
        }
        val description =
            blocks
                .filter { it.isProse() }
                .map { it.text() }
                .joinToString("\n\n")
                .blankToNull()
        val start = CLOCK.find(line)?.destructured?.let { (clock, meridiem) -> parseClock(clock, meridiem.ifEmpty { null }) }
        return dates.map { date ->
            ScrapedEvent(
                title = title,
                description = description,
                eventType = eventType(title),
                eventDate = date,
                startTime = start,
                imageUrl = blocks.firstNotNullOfOrNull(::imageOf),
                sourceUrl = sourceUrl,
                sourceId = "${EventSource.Z_BAR.sourceIdPrefix}$date-${SlugGenerator.slugify(title)}",
                ticketUrl = ticketUrl,
                free = detectFree(priceNote = description),
                promoters = blocks.firstNotNullOfOrNull(::promotersIn).orEmpty()
            )
        }
    }

    private fun datesIn(line: String): List<LocalDate> {
        val matches = DATE.findAll(line).toList()
        val lineYear = matches.lastOrNull { it.groupValues[YEAR].isNotEmpty() }?.groupValues?.get(YEAR) ?: return emptyList()
        return matches.mapNotNull { match ->
            val (day, month, year) = match.destructured
            try {
                LocalDate.of(year.ifEmpty { lineYear }.toInt(), month.toInt(), day.toInt())
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    private fun Element.opensSection(): Boolean = tagName() == "p" && hasClass("has-background") && LATIN.containsMatchIn(text())

    /** A paragraph of text: not a highlighted line, a flyer credit or a bare link. */
    private fun Element.isProse(): Boolean = tagName() == "p" && !hasClass("has-background") && "©" !in text() && !LINK_TEXT.containsMatchIn(text())

    private fun ticketLink(block: Element): String? =
        block
            .select("a[href]")
            .map { it.absUrl("href") }
            .firstOrNull { TICKET_LINK.containsMatchIn(it) }
            ?.substringBefore('?')

    private fun titleFromEventbrite(url: String): String? =
        EVENTBRITE_SLUG
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.split('-')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

    private fun imageOf(block: Element): String? =
        block.selectFirst("img[src]")?.let { img ->
            img.attr("data-orig-file").blankToNull() ?: img.absUrl("src").substringBefore('?').blankToNull()
        }

    private fun promotersIn(block: Element): List<String>? =
        CREDIT
            .find(block.text())
            ?.groupValues
            ?.get(1)
            ?.replace(URL, " ")
            ?.split(" / ")
            ?.mapNotNull { it.replace(TRAILING_CITY, "").trim().blankToNull() }
            ?.filterNot { it.equals("Berlin", ignoreCase = true) }
            ?.takeIf { it.isNotEmpty() }

    private fun eventType(title: String): String =
        when {
            READING_TITLE.containsMatchIn(title) -> EventType.READING
            SCREENING_TITLE.containsMatchIn(title) -> EventType.SCREENING
            else -> EventType.COMEDY
        }.name

    private companion object {
        val LATIN = Regex("""\p{IsLatin}""")

        /** `21.10.2026`, `14.11,2026` (a typo), or `29.10.` sharing the year of a later date on the line. */
        val DATE = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})(?:[.,](\d{4}))?(?![\d:])""")
        const val YEAR = 3

        /**
         * The first clock on the line, `20:00`, `08:30pm` or `08:00 pm`; a second show's time is ignored. Only a colon
         * separates a clock, so the date typo `14.11,2026` is not one.
         */
        val CLOCK = Regex("""(?<!\d)(\d{1,2}:\d{2})(?!\d)\s*(?:([ap])\.?\s?m\b)?""", RegexOption.IGNORE_CASE)

        val TICKET_LINK = Regex("""eventbrite\.[a-z.]+/e/|ticket-souq\.com/""")
        val EVENTBRITE_SLUG = Regex("""eventbrite\.[a-z.]+/e/([a-z0-9-]+?)(?:-tickets-\d+)?(?:[/?]|$)""")

        /** `Flyer © promo – Veranstalter: Chris Doering, Berlin`, also `Veranstalterin:` and `Veranstaltende:`. */
        val CREDIT = Regex("""Veranstalt\p{L}*:\s*(.+)$""")
        val TRAILING_CITY = Regex("""[,\s]+Berlin$""")
        val URL = Regex("""https?://\S+""")
        val LINK_TEXT = Regex("""https?://|www\.""")

        val READING_TITLE = Regex("""lesebühne|lesung|\bslam\b""", RegexOption.IGNORE_CASE)
        val SCREENING_TITLE = Regex("""kurzfilm|\bfilm""", RegexOption.IGNORE_CASE)
    }
}

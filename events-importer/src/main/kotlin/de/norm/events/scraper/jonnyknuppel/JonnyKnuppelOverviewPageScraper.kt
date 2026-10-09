package de.norm.events.scraper.jonnyknuppel

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isPlaceholderName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.scraper.withBilingualDescriptionSplit
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for the Jonny Knüppel calendar, one `li.event-item` per night in the homepage menu.
 *
 * The printed `DDMM-DDMM` carries no year, so the year comes from the night's `data-event-end`
 * timestamp: the start is the last `DDMM` on or before that end. The page has no link per night, so
 * the `sourceId` is the start date and time, which stays when a `TBA` title gets its name.
 */
class JonnyKnuppelOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("li.event-item")
        logger.info { "Found ${items.size} night(s) in the Jonny Knüppel calendar" }
        return items.mapSkippingFailures(logger, "Jonny Knüppel night") { parseNight(it, baseUrl) }
    }

    @Suppress("ReturnCount") // Guard clauses for the required title and date are clearer than nesting.
    private fun parseNight(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = item.textAt(".event-title")
        if (title.isNullOrBlank() || isPlaceholderName(title)) {
            logger.debug { "Skipping a Jonny Knüppel night titled '$title'" }
            return null
        }
        val end = parseEnd(item.attr("data-event-end"))
        val eventDate = end?.let { startDate(item.textAt(".event-date"), it.toLocalDate()) }
        if (end == null || eventDate == null) {
            logger.warn { "Could not date Jonny Knüppel night '$title' (${item.textAt(".event-date")}, end '${item.attr("data-event-end")}'), skipping" }
            return null
        }
        val startTime = parseTime(item.textAt(".event-time")?.substringBefore('-'), HHMM)
        val detail = item.selectFirst(".event-detail")
        val (priceBoxOffice, priceNote) = parsePrice(detail?.let { sectionAfter(it, ENTRY_HEADING) })

        return ScrapedEvent(
            title = title,
            description = detail?.let(::description),
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = startTime,
            endDate = end.toLocalDate(),
            endTime = end.toLocalTime(),
            sourceUrl = baseUrl,
            sourceId = "${EventSource.JONNY_KNUPPEL.sourceIdPrefix}$eventDate${startTime?.let { "-${it.format(HHMM)}" }.orEmpty()}",
            priceBoxOffice = priceBoxOffice,
            priceNote = priceNote,
            artists = detail?.let { sectionAfter(it, LINEUP_HEADING) }?.let(::lineup).orEmpty()
        ).withBilingualDescriptionSplit()
    }

    private fun parseEnd(text: String): LocalDateTime? =
        try {
            LocalDateTime.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    /** The first `DDMM` of [printed] in the year that puts it on or before [endDate]. */
    private fun startDate(
        printed: String?,
        endDate: LocalDate
    ): LocalDate? {
        val monthDay =
            printed
                ?.let { PRINTED_DATE.find(it) }
                ?.destructured
                ?.let { (day, month) -> runCatching { MonthDay.of(month.toInt(), day.toInt()) }.getOrNull() }
                ?: return null
        val sameYear = monthDay.atYear(endDate.year)
        return if (sameYear > endDate) monthDay.atYear(endDate.year - 1) else sameYear
    }

    /** The blurb: a paragraph of its own before any section heading, with its line breaks. */
    private fun description(detail: Element): String? =
        detail
            .select("> p:not([class])")
            .firstOrNull { it.previousElementSibling()?.hasClass(HEADING_CLASS) != true }
            ?.wholeText()
            ?.trim()
            ?.ifBlank { null }

    /** The element after the section heading [heading], such as the `ul` under `Line-up`. */
    private fun sectionAfter(
        detail: Element,
        heading: String
    ): Element? = detail.select("> p.$HEADING_CLASS").firstOrNull { it.text().equals(heading, ignoreCase = true) }?.nextElementSibling()

    private fun lineup(list: Element): List<ScrapedArtist> =
        list
            .select("> li")
            .map { it.text() }
            .map(::stripArtistSuffix)
            .filter { it.isNotBlank() && !isNonArtistName(it) }
            .distinct()
            .map { ScrapedArtist(name = it, role = "DJ") }

    /** One amount is the door price; a price by arrival time (`22-23 Uhr: 10€`, `Ab 23 Uhr: 15-20€`) is a note. */
    private fun parsePrice(entry: Element?): Pair<BigDecimal?, String?> {
        val lines = entry?.textLines().orEmpty()
        val amounts = euroAmounts(lines.joinToString(" "))
        return when {
            lines.isEmpty() -> null to null
            lines.size == 1 && amounts.size == 1 -> amounts.single() to null
            else -> null to lines.joinToString("; ")
        }
    }

    private companion object {
        const val HEADING_CLASS = "legal-section-heading"
        const val LINEUP_HEADING = "Line-up"
        const val ENTRY_HEADING = "Eintritt"

        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /** The first `DDMM` of `0910-1010` or `0409`. */
        val PRINTED_DATE = Regex("""^\s*(\d{2})(\d{2})(?:\D|$)""")
    }
}

package de.norm.events.scraper.urania

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure HTML parser for the Urania's `/kalender/` page — the house's whole upcoming programme,
 * server-rendered in one page with no pagination.
 *
 * The calendar groups events under `div.c-event-calendar_day[data-day]`, whose attribute states the
 * full date in one machine-readable token (`03-do-09-2026` — day, weekday, month, year). That is
 * read directly rather than combining a month heading with a day cell, and a day may hold more than
 * one event.
 *
 * Each `div.c-event-calendar-item` carries a clock, the programme strand it belongs to (`h5`), the
 * title (`h3`), the format (`h6`), the billed speakers and a Reservix ticket link, plus the link to
 * its own `/event/<slug>/` page.
 *
 * The site's own `/wp-json/reservixapi/v1/events` endpoint was evaluated and rejected despite
 * ADR-007's preference for JSON: it returns the same 17 events but carries no urania.de event URL —
 * only the external ticket link — and omits the format label and the concession prices, offering a
 * bare `minPrice` instead. The rendered pages are the richer source and the only one that states
 * the events' canonical URLs.
 *
 * **No source states which hall an event is in.** Neither the calendar, the event pages, the
 * Reservix shop nor that API name the Humboldtsaal or the Kleistsaal, so events are imported
 * against the house rather than split between its halls, which is exactly what all four of those
 * sources describe.
 *
 * @see UraniaEventPageScraper for the event pages (description, price, poster).
 * @see UraniaWebsiteImporter for the HTTP fetch orchestrator.
 */
class UraniaCalendarPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event on the calendar page.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve event links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed items without aborting the import
        val events =
            document.select("div.c-event-calendar_day[data-day]").flatMap { day ->
                val date = parseDayToken(day.attr("data-day"))
                if (date == null) {
                    logger.warn { "Urania day token '${day.attr("data-day")}' is not a date, skipping its events" }
                    emptyList()
                } else {
                    day.select("div.c-event-calendar-item").mapNotNull { item ->
                        try {
                            parseItem(item, date, baseUrl)
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse Urania calendar item, skipping" }
                            null
                        }
                    }
                }
            }
        logger.info { "Found ${events.size} event(s) on the Urania calendar" }
        return events
    }

    /** Parses one calendar item into a [ScrapedEvent], or `null` when it has no link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseItem(
        item: Element,
        date: LocalDate,
        baseUrl: String
    ): ScrapedEvent? {
        val href = item.attrAt("a.c-event-calendar-item_content", "href") ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        val title = item.textAt("h3.o-h3")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Urania calendar item '$slug' has no title, skipping" }
            return null
        }
        val format = item.textAt("h6.o-h6")

        return ScrapedEvent(
            title = title,
            subtitle = uraniaSubtitle(series = item.textAt("h5.o-h6"), format = format),
            // The calendar carries no prose at all; its one text block is the speaker billing.
            eventType = uraniaEventType(format),
            eventDate = date,
            startTime = parseTime(item.textAt(".c-event-calendar-item_time")?.substringBefore(CLOCK_SUFFIX)?.trim()),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.URANIA.sourceIdPrefix}$slug",
            ticketUrl = item.hrefAt(".c-event-calendar-item_ticket-link a"),
            artists = uraniaSpeakers(item.textAt(".c-event-calendar-item_content_text"))
        )
    }

    /**
     * Reads the full date out of a `data-day` token (`"03-do-09-2026"`): day, weekday abbreviation,
     * month and year. Returns `null` when the token is not one, so a future markup change drops the
     * day rather than minting events on a wrong date.
     */
    private fun parseDayToken(token: String): LocalDate? {
        val match = DAY_TOKEN.find(token.trim()) ?: return null
        val (day, month, year) = match.destructured
        return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }
}

/**
 * Joins the programme strand and the format into one subtitle
 * (`"bzw.:BEZIEHUNGSWESEN · Podiumsgespräch"`).
 *
 * The strand is the series a talk belongs to and the format is what kind of evening it is; neither
 * is a musical genre, so neither is stored as one. Either may be absent.
 */
internal fun uraniaSubtitle(
    series: String?,
    format: String?
): String? =
    listOfNotNull(series?.takeIf { it.isNotBlank() }, format?.takeIf { it.isNotBlank() })
        .joinToString(SUBTITLE_SEPARATOR)
        .takeIf { it.isNotBlank() }

/** `"03-do-09-2026"` — day, weekday abbreviation, month, year. */
private val DAY_TOKEN = Regex("""^(\d{1,2})-[a-zäöü]+-(\d{1,2})-(\d{4})$""", RegexOption.IGNORE_CASE)

/** Separates the strand from the format in the assembled subtitle. */
private const val SUBTITLE_SEPARATOR = " · "

/** The trailing `Uhr` the venue appends to its start time. */
private const val CLOCK_SUFFIX = "Uhr"

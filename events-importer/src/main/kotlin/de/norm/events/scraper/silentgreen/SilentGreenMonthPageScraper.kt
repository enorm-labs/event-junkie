package de.norm.events.scraper.silentgreen

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure HTML parser for one month of silent green's TYPO3 (`tx_news`) calendar (`/programm`,
 * `/programm/<yyyy>/<m>`): `.eventList-day` blocks of `.eventList-event` rows, a run appearing
 * once per open day (the August page holds 53 rows for 19 events, 23 of them one exhibition).
 * Every row is emitted as its own dated event; [SilentGreenWebsiteImporter] fetches each detail
 * page once and folds an exhibition's days into one run (ADR-029, #337), while a festival's days
 * keep their own lineups. The rendered date is year-less (`"Sa 01.08."`); the `.current-month`
 * heading (`"August 2026"`) supplies the year, and an unreadable heading yields nothing rather
 * than a guess. The venue publishes no prices and no genre; doors, poster and blurb are on the
 * detail page ([SilentGreenEventDetails]).
 *
 * @see SilentGreenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.silent-green.net/programm">silent green calendar</a>
 */
class SilentGreenMonthPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every listed day of one month page.
     *
     * @param baseUrl the URL the page was fetched from, for the relative detail links.
     * @return one [ScrapedEvent] per row, in page order; empty for a month with no programme,
     * which is how [SilentGreenWebsiteImporter] knows to stop.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val month = parseMonthHeading(document)
        if (month == null) {
            logger.warn { "silent green month page $baseUrl has no readable month heading, skipping it" }
            return emptyList()
        }

        val rows = document.select(EVENT_ROW_SELECTOR)
        logger.info { "Found ${rows.size} calendar row(s) for $month on $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip a single malformed row without aborting the import
        return rows.mapNotNull { row ->
            try {
                parseRow(row, month, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse silent green calendar row on $baseUrl, skipping" }
                null
            }
        }
    }

    /**
     * Parses one `.eventList-event` row, or `null` when the detail link, title or date is missing.
     * The raw title is read twice: [parseEventStatus] and the sold-out check need the annotations
     * (`"… – ausverkauft"`), [cleanEventTitle] removes them from what is stored.
     */
    @Suppress("ReturnCount") // Guard clauses per missing required field are clearer than nesting
    private fun parseRow(
        row: Element,
        month: YearMonth,
        baseUrl: String
    ): ScrapedEvent? {
        val detailHref = row.attrAt("$TITLE_SELECTOR a", "href")
        if (detailHref == null) {
            logger.warn { "Calendar row has no detail link, skipping" }
            return null
        }
        // The href carries the calendar's tx_news day/month/year and cHash parameters; the bare path
        // serves the same page and is the run's identity, so the query is dropped.
        val sourceUrl = resolveUrl(baseUrl, detailHref.substringBefore('?'))

        val rawTitle = row.textAt("$TITLE_SELECTOR h3")
        if (rawTitle.isNullOrBlank()) {
            logger.warn { "Calendar row for $sourceUrl has no title, skipping" }
            return null
        }

        val eventDate = parseRowDate(row, month)
        if (eventDate == null) {
            logger.warn { "Could not parse date for '$rawTitle' on $sourceUrl, skipping" }
            return null
        }

        val title = cleanEventTitle(rawTitle)
        val eventType = silentGreenEventType(row.textAt(".eventList-event-cat"), title)
        val hall = row.textAt(".eventList-event-location")
        val subLine =
            row
                .textAt("$TITLE_SELECTOR p")
                ?.replace(NON_BREAKING_SPACE, ' ')
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        return ScrapedEvent(
            title = title,
            subtitle = silentGreenSubtitle(subLine),
            eventType = eventType,
            eventDate = eventDate,
            // The calendar's start time is the show's own start ("Beginn"); doors are on the detail page.
            startTime = parseTime(row.textAt(".eventList-event-starttime")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SILENT_GREEN.sourceIdPrefix}$eventDate-${detailSlug(sourceUrl)}",
            ticketUrl = row.hrefAt(".eventList-event-ticket a"),
            soldOut = rawTitle.contains(SOLD_OUT_MARKER, ignoreCase = true),
            status = parseEventStatus(rawTitle),
            artists = silentGreenArtists(title, eventType, hall),
            promoters = silentGreenPresenters(subLine)
        )
    }

    /**
     * The row's date from the day and month it renders (`"Sa 01.08."`) and the page's year.
     */
    private fun parseRowDate(
        row: Element,
        month: YearMonth
    ): LocalDate? {
        val match = row.textAt(".eventList-event-date")?.let { DAY_MONTH_PATTERN.find(it) } ?: return null
        val (day, monthOfYear) = match.destructured
        return runCatching { LocalDate.of(month.year, monthOfYear.toInt(), day.toInt()) }.getOrNull()
    }

    /**
     * The month from the calendar heading (`"August 2026"`), matched on its first three letters via
     * [parseGermanMonthAbbreviation]; `"Mär"` is a spelling that table accepts.
     */
    private fun parseMonthHeading(document: Document): YearMonth? {
        val heading = document.textAt(MONTH_HEADING_SELECTOR)?.split(' ').orEmpty()
        val month = parseGermanMonthAbbreviation(heading.firstOrNull()?.take(GERMAN_MONTH_PREFIX))
        val year = heading.getOrNull(1)?.toIntOrNull()
        if (month == null || year == null) return null
        return runCatching { YearMonth.of(year, month) }.getOrNull()
    }

    /**
     * The stable identity from the last path segment of the detail URL (`/programm/detail/htrk` to
     * `htrk`), combined with the date for the `sourceId`, since the URL is shared by every day of a
     * run.
     */
    private fun detailSlug(url: String): String = silentGreenDetailSlug(url)

    private companion object {
        /** One calendar row, scoped to a day block so nothing else on the page can match. */
        const val EVENT_ROW_SELECTOR = ".eventList-day .eventList-event"

        /** The row's title block: the detail link, the `<h3>` billing, and the sub-line under it. */
        const val TITLE_SELECTOR = ".eventList-event-title"

        /** The month switcher's current entry, e.g. "August 2026". */
        const val MONTH_HEADING_SELECTOR = "#calendar-timeline .current-month"

        /** The day and month of a year-less `"Sa 01.08."` date block. */
        val DAY_MONTH_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.""")

        /** Letters of a German month name that identify it — see [parseMonthHeading]. */
        const val GERMAN_MONTH_PREFIX = 3

        /** The venue's sold-out annotation, appended to the title ("HTRK + Loraine James – ausverkauft"). */
        const val SOLD_OUT_MARKER = "ausverkauft"

        /** Jsoup renders the sub-line's trailing `&nbsp;` as this; it is padding, not text. */
        const val NON_BREAKING_SPACE = '\u00A0'
    }
}

/** The `<slug>` of a `/programm/detail/<slug>` URL, which is a run's identity across its days. */
internal fun silentGreenDetailSlug(url: String): String = URI(url).path.trimEnd('/').substringAfterLast('/')

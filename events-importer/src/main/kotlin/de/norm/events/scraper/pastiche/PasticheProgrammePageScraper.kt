package de.norm.events.scraper.pastiche

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.parseEnglishWeekdayAbbreviation
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitBackToBack
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.MonthDay
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure parser for Pastiche's Cargo programme page, one month at a time. Each event is a `column-set` under
 * `div.pages`: the first `column-unit` holds `Thurs, 22nd Oct` over `21:00 - 23:00`, the second the title in
 * `<b>` over a format line (`Live Set*`), the third a short text and a button to the event's own page.
 *
 * The page prints no year, so the year is the one nearest today on the printed weekday ([inferYearForWeekday]).
 * A workshop and the record fair are out of scope (EVENT_SCOPE.md §3.2, §3.3). A night whose format line names
 * no format is a concert: the venue asks guests to treat every listening event as one.
 */
class PasticheProgrammePageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.pages column-set:has(> column-unit[slot=2])").filter { it.titleText() != null }
        logger.info { "Found ${rows.size} row(s) on the Pastiche programme" }
        return rows.mapSkippingFailures(logger, "a Pastiche programme row") { parseRow(it, sourceUrl) }
    }

    private fun parseRow(
        row: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = row.titleText() ?: return null
        val format =
            row
                .unit(TITLE_SLOT)
                ?.text()
                ?.collapseWhitespace()
                ?.let { formatLine(it, title) }
        if (isOutOfScope(title, format)) {
            logger.debug { "Pastiche '$title' is a workshop or a record fair, skipping" }
            return null
        }
        val date = readDate(row.schedule())
        if (date == null) {
            logger.warn { "Pastiche '$title' has no readable date ('${row.schedule()}'), skipping" }
            return null
        }
        return toEvent(row, title, format, date, sourceUrl)
    }

    private fun toEvent(
        row: Element,
        title: String,
        format: String?,
        date: LocalDate,
        sourceUrl: String
    ): ScrapedEvent {
        val times = TIME_RANGE.find(row.schedule())
        val start = times?.groupValues?.get(1)?.let(::readClock)
        val end =
            times
                ?.groupValues
                ?.get(2)
                ?.blankToNull()
                ?.let(::readClock)
        val details = row.unit(DETAILS_SLOT)
        val button = details?.selectFirst("a[href]:not([href^=#])")
        val pageUrl = button?.attr("href")?.blankToNull()?.let { resolveUrl(sourceUrl, it) }
        val type = eventTypeOf(title, format)
        return ScrapedEvent(
            title = title,
            subtitle = format,
            description = details?.clone()?.apply { select("a").remove() }?.paragraphText(),
            eventType = type ?: EventType.CONCERT.name,
            typeIsFallback = type == null,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            sourceUrl = pageUrl ?: sourceUrl,
            sourceId = EventSource.PASTICHE.sourceIdPrefix + (pageUrl?.let(::pageSlug) ?: "$date-${SlugGenerator.slugify(title)}"),
            ticketUrl = pageUrl?.takeIf { button.text().collapseWhitespace().equals(TICKETS, ignoreCase = true) },
            free = button?.text()?.collapseWhitespace().equals(FREE, ignoreCase = true),
            artists = artistsOf(title, format)
        )
    }

    /** `Thurs, 22nd Oct` at the year nearest today that falls on the printed weekday, or null for an unreadable date. */
    private fun readDate(schedule: String): LocalDate? {
        val groups = DAY.find(schedule)?.groupValues ?: return null
        val month = MONTHS[groups[MONTH].take(MONTH_PREFIX).lowercase()] ?: return null
        val monthDay =
            try {
                MonthDay.of(month, groups[DAY_OF_MONTH].toInt())
            } catch (_: DateTimeException) {
                return null
            }
        return inferYearForWeekday(monthDay, parseEnglishWeekdayAbbreviation(groups[WEEKDAY].take(MONTH_PREFIX)), clock)
    }

    companion object {
        /** Whether a row is a participation format or a market (EVENT_SCOPE.md §3.2, §3.3), on its title or format line. */
        fun isOutOfScope(
            title: String,
            format: String?
        ): Boolean = listOfNotNull(title, format).any { OUT_OF_SCOPE.containsMatchIn(it) }

        /** The event type the format line names, else the title, or null for a night that names none. */
        fun eventTypeOf(
            title: String,
            format: String?
        ): String? =
            listOfNotNull(format, title).firstNotNullOfOrNull { text ->
                FORMATS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
            }

        private const val DATE_SLOT = 0
        private const val TITLE_SLOT = 1
        private const val DETAILS_SLOT = 2
        private const val WEEKDAY = 1
        private const val DAY_OF_MONTH = 2
        private const val MONTH = 3
        private const val MONTH_PREFIX = 3
        private const val TICKETS = "Tickets"
        private const val FREE = "Free"
        private val DAY = Regex("""^(\p{L}+)\.?,?\s+(\d{1,2})(?:st|nd|rd|th)?\.?\s+(\p{L}+)""", RegexOption.IGNORE_CASE)
        private val TIME_RANGE = Regex("""(\d{1,2}[:.]\d{2})\s*(?:[-–]\s*(\d{1,2}[:.]\d{2}))?""")
        private val MONTHS = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(MONTH_PREFIX).lowercase() }
        private val OUT_OF_SCOPE = Regex("""\bworkshops?\b|\brecord fairs?\b|\bplattenb(?:ö|oe)rse\b""", RegexOption.IGNORE_CASE)
        private val LIVE = Regex("""\blive\b""", RegexOption.IGNORE_CASE)
        private val DJ = Regex("""\bdj\b""", RegexOption.IGNORE_CASE)
        private val FORMATS =
            listOf(
                Regex("""\bquiz""", RegexOption.IGNORE_CASE) to EventType.QUIZ.name,
                Regex("""\bscreening\b|\bfilm\b|\bcinema\b""", RegexOption.IGNORE_CASE) to EventType.SCREENING.name,
                Regex("""\btalks?\b|\breading\b|\bbook\b|\bpanel\b|\bin conversation\b""", RegexOption.IGNORE_CASE) to EventType.READING.name,
                Regex("""\blive\b|\bdj\b|\blistening\b|\bconcert\b|\balbum\b""", RegexOption.IGNORE_CASE) to EventType.CONCERT.name
            )

        /** The act a `Live` or `DJ` format line bills under the title; any other night names nobody. */
        private fun artistsOf(
            title: String,
            format: String?
        ): List<ScrapedArtist> {
            if (format == null || isNonArtistName(title)) return emptyList()
            val role =
                when {
                    DJ.containsMatchIn(format) -> "DJ"
                    LIVE.containsMatchIn(format) -> "HEADLINER"
                    else -> return emptyList()
                }
            return splitBackToBack(title).map { ScrapedArtist(name = it, role = role) }
        }

        /** The row's text after its title, without the asterisk the venue puts after a format. */
        private fun formatLine(
            text: String,
            title: String
        ): String? =
            (if (text.startsWith(title)) text.removePrefix(title) else text.replaceFirst(title, ""))
                .trim()
                .trimEnd('*')
                .trim()
                .blankToNull()

        private fun readClock(text: String): LocalTime? = parseClock(text.replace('.', ':'))

        private fun pageSlug(url: String): String = URI(url).path.trim('/')

        private fun Element.unit(slot: Int): Element? = selectFirst("> column-unit[slot=$slot]")

        /** The first column's text: the date, then the time range. */
        private fun Element.schedule(): String = unit(DATE_SLOT)?.text()?.collapseWhitespace().orEmpty()

        private fun Element.titleText(): String? =
            unit(TITLE_SLOT)
                ?.select("b")
                ?.firstNotNullOfOrNull {
                    it
                        .text()
                        .collapseWhitespace()
                        .trimEnd('*')
                        .trim()
                        .blankToNull()
                }
    }
}

package de.norm.events.scraper.zku

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.billsTradeFairOrConference
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.dateOrNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

/**
 * Pure parser for the calendar on ZK/U's TYPO3 homepage. Each day is a hidden popup,
 * `div.calendar-events#d-2026-10-24`, holding one `.list-item` per entry: an `h2` or `h3.h2` title, a
 * `p.date` line (`24 OCTOBER 2026 / 21:00 – 03:00`), an optional subtitle and the link to
 * `/timeline/<slug>/`. The popup's id gives the day, so an entry over two days is one row a day.
 *
 * An exhibition prints its run and its opening (`27 NOVEMBER – 03 DECEMBER 2026 / OPENING ON 26 NOVEMBER, 19:00`), and the
 * calendar repeats it on each day of the run and on days outside it. It is one row over the run ([exhibitionRun]).
 *
 * The site names no category. Markets, workshops, open studios and meetings are dropped
 * ([isZkuParticipationFormat]), as is a conference; the rest is typed from the title and the
 * subtitle in the house's own words ([ZKU_FORMATS]).
 */
class ZkuCalendarScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val days = document.select("div.calendar-events[id^=d-]")
        val entries = days.flatMap { day -> day.select(".list-item").map { day.id() to it } }
        val events =
            entries
                .mapSkippingFailures(
                    logger,
                    "a ZK/U calendar entry"
                ) { (dayId, item) -> parseEntry(dayId, item, sourceUrl) }
                .distinctBy { it.sourceId }
        logger.info { "Found ${events.size} programme entr(ies) over ${days.size} day(s) of the ZK/U calendar" }
        return events
    }

    private fun parseEntry(
        dayId: String,
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val dateLine = item.selectFirst("p.date")?.text()
        val run = exhibitionRun(dateLine)
        val date = run?.start ?: parseIsoDate(dayId.removePrefix("d-")) ?: return null
        val title =
            item
                .selectFirst(TITLE)
                ?.text()
                ?.collapseWhitespace()
                .blankToNull() ?: return null
        val subtitle =
            item
                .selectFirst("p.list-item__description")
                ?.text()
                ?.collapseWhitespace()
                .blankToNull()
        if (isZkuParticipationFormat(title, subtitle) || billsTradeFairOrConference(title, subtitle)) {
            logger.debug { "Skipping ZK/U entry '$title': not a stage programme" }
            return null
        }
        val href =
            item
                .selectFirst("a.list-item__link")
                ?.attr("href")
                .blankToNull()
                ?.let { resolveUrl(sourceUrl, it) } ?: return null
        val slug = URI(href).path.trimEnd('/').substringAfterLast('/')
        val (start, end) = if (run != null) run.openingTime to null else parseTimes(dateLine)
        val eventType = if (run != null) EventType.EXHIBITION.name else inferVenueFormatType(title, subtitle, ZKU_FORMATS)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            typeIsFallback = eventType == EventType.OTHER.name,
            eventDate = date,
            startTime = start,
            endDate = run?.end ?: end?.let { endOn(date, start, it) },
            endTime = end,
            imageUrl =
                item
                    .selectFirst(".list-item__img[data-src]")
                    ?.attr("data-src")
                    .blankToNull()
                    ?.let { resolveUrl(sourceUrl, it) },
            sourceUrl = href,
            sourceId = "${EventSource.ZKU.sourceIdPrefix}$slug-$date",
            artists = if (eventType == EventType.READING.name) speakerOf(title) else emptyList()
        )
    }

    /** The start and, for a range, the end of a date line's clock: `/ 21:00 – 03:00`, `/ starting from 18:30`. */
    private fun parseTimes(dateLine: String?): Pair<LocalTime?, LocalTime?> {
        val match = CLOCK_RANGE.find(dateLine.orEmpty().substringAfter('/', "")) ?: return null to null
        return clock(match.groupValues[1]) to match.groupValues[2].blankToNull()?.let(::clock)
    }

    /** The run of a date line that names its first and last day and an opening; null for any other line. */
    private fun exhibitionRun(dateLine: String?): ExhibitionRun? {
        val match = dateLine?.let(RUN_LINE::find) ?: return null
        val groups = match.groupValues
        val year = groups[5].toInt()
        val end = dayOf(groups[3], groups[4], year) ?: return null
        val first = dayBefore(end, groups[1], groups[2]) ?: return null
        val opening = OPENING_DAY.find(groups[6])
        val openingDay = opening?.let { dayBefore(end, it.groupValues[1], it.groupValues[2]) }
        val start = listOfNotNull(first, openingDay).min()
        val openingTime =
            opening
                ?.takeIf { openingDay == start }
                ?.groupValues
                ?.get(3)
                .blankToNull()
                ?.let(::clock)
        return ExhibitionRun(start, end, openingTime)
    }

    /** The day and month at or before [end], which alone carries the year. */
    private fun dayBefore(
        end: LocalDate,
        day: String,
        month: String
    ): LocalDate? = dayOf(day, month, end.year)?.let { if (it > end) it.minusYears(1) else it }

    private fun dayOf(
        day: String,
        month: String,
        year: Int
    ): LocalDate? =
        Month.entries
            .firstOrNull { it.name.equals(month, ignoreCase = true) }
            ?.let { dateOrNull { LocalDate.of(year, it, day.toInt()) } }

    private data class ExhibitionRun(
        val start: LocalDate,
        val end: LocalDate,
        val openingTime: LocalTime?
    )

    private fun clock(text: String): LocalTime? = parseTime(text.replace('.', ':').padStart(CLOCK_LENGTH, '0'))

    /** The speaker of a talk billed `Name Surname: Topic`. */
    private fun speakerOf(title: String): List<ScrapedArtist> =
        SPEAKER_PREFIX
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.let { listOf(ScrapedArtist(name = it)) }
            .orEmpty()

    private companion object {
        /** The entry's title: an `h2` until November 2026, an `h3.h2` since (#4812). */
        const val TITLE = "h2, h3.h2"

        val CLOCK_RANGE = Regex("""(\d{1,2}[:.]\d{2})(?:\s*[–-]\s*(\d{1,2}[:.]\d{2}))?""")
        const val CLOCK_LENGTH = 5

        /** A run and its opening: `26 NOVEMBER – 06 DECEMBER 2026 / SELECTED OPENING HOURS OR BY APPOINTMENT`. */
        val RUN_LINE = Regex("""^\s*(\d{1,2})\s+(\p{L}+)\s*[–-]\s*(\d{1,2})\s+(\p{L}+)\s+(\d{4})\s*/(.*\bopening\b.*)$""", RegexOption.IGNORE_CASE)

        /** The vernissage in a run's line: `OPENING ON 26 NOVEMBER, 19:00`. */
        val OPENING_DAY = Regex("""\bopening\s+on\s+(\d{1,2})\s+(\p{L}+)(?:,?\s+(\d{1,2}[:.]\d{2}))?""", RegexOption.IGNORE_CASE)

        /** Two to four capitalised words before a colon: `Dorothee Richter: Feminist Perspectives On Curating`. */
        val SPEAKER_PREFIX = Regex("""^(\p{Lu}[\p{L}.'-]+(?:\s+\p{Lu}[\p{L}.'-]+){1,3}):\s""")
    }
}

/**
 * The house's own format words, checked before the shared title cues. FOOD & FOOTAGE is its
 * film-and-dinner series, and its curatorial lectures are billed as talks.
 */
private val ZKU_FORMATS: Map<String, String> =
    linkedMapOf(
        "food & footage" to EventType.SCREENING.name,
        "film" to EventType.SCREENING.name,
        "movie" to EventType.SCREENING.name,
        "dj set" to EventType.PARTY.name,
        "talk" to EventType.READING.name,
        "lecture" to EventType.READING.name,
        "reading" to EventType.READING.name,
        "panel" to EventType.READING.name,
        "concert" to EventType.CONCERT.name,
        "performance" to EventType.SHOW.name,
        "festival" to EventType.FESTIVAL.name
    )

/** A format visitors take part in rather than watch, in the title or the subtitle. */
private val PARTICIPATION_FORMAT =
    Regex(
        """markt|\bmarket\b|\bworkshops?\b|\bopen studios?\b|\bopenhaus\b|editorial meeting|reading group|\bch\.\s*\d|\bknit|\bcourse\b|\bassembly\b""",
        RegexOption.IGNORE_CASE
    )

/** An academy in the title is a hands-on day (`Berlin Cake Academy`). A subtitle may name the curatorial school. */
private val ACADEMY_TITLE = Regex("""\bacademy\b""", RegexOption.IGNORE_CASE)

/**
 * Whether a ZK/U entry is a participation format (EVENT_SCOPE.md §3.2): a market, a workshop, the
 * residency's OPENHAUS open studios, an editorial meeting, a reading group, a knitting course, an
 * assembly or a hands-on academy. A reading-group session is billed by its chapter (`Ch. 12, in full`).
 */
internal fun isZkuParticipationFormat(
    title: String,
    subtitle: String?
): Boolean = PARTICIPATION_FORMAT.containsMatchIn("$title ${subtitle.orEmpty()}") || ACADEMY_TITLE.containsMatchIn(title)

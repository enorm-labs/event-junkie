package de.norm.events.scraper.liquidrom

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FOUR_DIGIT_CLOCK
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.endOn
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure parser for Liquidrom's `events.php`, which holds two programmes. The Sound Sessions are DJ nights in the pool,
 * generated rows under a `Oktober 2026` month heading, each `DI, 27.10. · 19 – 23 UHR`, `Sound Session mit <DJ>` and a
 * genre line. The DOME 36 specials are hand-written blocks with a year-less `18. November | 19:30 & 21:30 Uhr` line,
 * one show per time, so each time is its own event; the year is the nearest occurrence.
 */
class LiquidromOverviewPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val sessions = soundSessions(document, baseUrl)
        val specials =
            document.select("ul.saunaslider > li > div[id]:has(h2):has(h5)").mapSkippingFailures(logger, "Liquidrom special") { special(it, baseUrl) }.flatten()
        logger.info { "Found ${sessions.size} Liquidrom Sound Session(s) and ${specials.size} special show(s)" }
        return (sessions + specials).dropPastEvents(clock) { dropped -> logger.info { "Dropped $dropped past Liquidrom event(s)" } }
    }

    private fun soundSessions(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        var year: Int? = null
        val rows =
            document.select("#sound_sessions .month h3, #sound_sessions .event-container").mapNotNull { element ->
                if (element.tagName() == "h3") {
                    year =
                        MONTH_HEADING
                            .find(element.text())
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    null
                } else {
                    year to element
                }
            }
        return rows.mapSkippingFailures(logger, "Liquidrom Sound Session") { (rowYear, row) -> soundSession(row, rowYear, baseUrl) }
    }

    private fun soundSession(
        row: Element,
        year: Int?,
        baseUrl: String
    ): ScrapedEvent? {
        val dateLine = row.selectFirst(".date")?.text().orEmpty()
        val title =
            row
                .selectFirst("p")
                ?.text()
                ?.let(::cleanEventTitle)
                .blankToNull()
        val match = SESSION_DATE.find(dateLine)
        if (title == null || match == null || year == null) {
            logger.warn { "Liquidrom Sound Session '$title' on '$dateLine' has no title, date or year heading, skipping" }
            return null
        }
        val part = match.groupValues
        val date = LocalDate.of(year, part[2].toInt(), part[1].toInt())
        val start = clock(part[3], part[4])
        val end = clock(part[5], part[6])
        return ScrapedEvent(
            title = title,
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            sourceUrl = baseUrl,
            sourceId = "${EventSource.LIQUIDROM.sourceIdPrefix}sound-session-$date",
            genre = row.selectFirst(".genre")?.text().blankToNull(),
            artists =
                title
                    .substringAfter(DJ_MARKER, "")
                    .blankToNull()
                    ?.let(::headlinersFromTitle)
                    .orEmpty()
                    .map { it.copy(role = "DJ") }
        )
    }

    private fun special(
        block: Element,
        baseUrl: String
    ): List<ScrapedEvent> {
        val title =
            block
                .selectFirst("h2")
                ?.text()
                ?.let(::cleanEventTitle)
                .blankToNull()
        val dateLine = block.selectFirst("h5")?.text().orEmpty()
        val date = SPECIAL_DATE.find(dateLine)?.let { yearless(it.groupValues[1].toInt(), it.groupValues[2]) }
        val times =
            date?.let {
                SHOW_TIME
                    .findAll(dateLine.substringAfter(it.dayOfMonth.toString()))
                    .mapNotNull { m ->
                        clock(m.groupValues[1], m.groupValues[2])
                    }.toList()
            }
        if (title == null || date == null || times.isNullOrEmpty()) {
            logger.warn { "Liquidrom special '$title' on '$dateLine' has no title, date or show time, skipping" }
            return emptyList()
        }
        val paragraphs = block.select("p")
        val presenter =
            paragraphs.firstNotNullOfOrNull {
                PRESENTER
                    .find(it.text())
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }
        return times.map { time ->
            ScrapedEvent(
                title = title,
                description =
                    paragraphs
                        .firstOrNull()
                        ?.textLines()
                        ?.joinToString("\n")
                        .blankToNull(),
                eventType = EventType.SHOW.name,
                eventDate = date,
                startTime = time,
                imageUrl = block.selectFirst("img[src]")?.absUrl("src").blankToNull(),
                sourceUrl = "$baseUrl#${block.id()}",
                sourceId = "${EventSource.LIQUIDROM.sourceIdPrefix}${block.id()}-$date-${time.format(FOUR_DIGIT_CLOCK)}",
                promoters = listOfNotNull(presenter)
            )
        }
    }

    private fun yearless(
        day: Int,
        monthName: String
    ): LocalDate? {
        val month = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(monthName, ignoreCase = true) } + 1
        if (month == 0) return null
        return inferYearForWeekday(MonthDay.of(month, day), weekday = null, clock = clock)
    }

    private fun clock(
        hour: String,
        minute: String
    ): LocalTime? = parseTime("${hour.padStart(2, '0')}:${minute.ifEmpty { "00" }}")

    private companion object {
        const val DJ_MARKER = " mit "
        val MONTH_HEADING = Regex("""\b(\d{4})\b""")

        /** `DI, 27.10. · 19 – 23 UHR`; a minute is printed only where it is not `00`. */
        val SESSION_DATE = Regex("""(\d{1,2})\.(\d{1,2})\.\s*·\s*(\d{1,2})(?::(\d{2}))?\s*[–-]\s*(\d{1,2})(?::(\d{2}))?""")
        val SPECIAL_DATE = Regex("""(\d{1,2})\.\s*(${GERMAN_MONTH_NAMES.joinToString("|")})""", RegexOption.IGNORE_CASE)
        val SHOW_TIME = Regex("""(\d{1,2}):(\d{2})""")
        val PRESENTER = Regex("""Präsentiert von\s+(.+)""", RegexOption.IGNORE_CASE)
    }
}

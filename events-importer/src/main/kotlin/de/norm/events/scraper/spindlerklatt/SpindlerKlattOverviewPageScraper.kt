package de.norm.events.scraper.spindlerklatt

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.NUMERIC_DAY_MONTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseNumericDayMonth
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalTime

/**
 * Pure parser for the club page's programme. Each night is a `.card`: an `<a>` that links the
 * night's ticket shop or promoter, or a `<div>` when there is no link. It holds a flyer
 * (`img.poster`), a date line (`Sa · 14.11. · 22:00 Uhr`, for an early night `Fr · 20.11. · 19–23 Uhr`)
 * and the title in `h3`. The date line prints no year, so the year comes from the weekday
 * ([inferYearForWeekday]), correct across the turn of the year. A title broken by `<br>` is
 * joined into one line.
 */
class SpindlerKlattOverviewPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select(".card")
        logger.info { "Found ${cards.size} night(s) on the Spindler & Klatt club page" }
        return cards.mapSkippingFailures(logger, "a Spindler & Klatt night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        card: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val dateLine =
            card
                .selectFirst(".meta")
                ?.text()
                ?.replace(WHITESPACE, " ")
                .orEmpty()
        val monthDay = parseNumericDayMonth(dateLine)
        if (monthDay == null) {
            logger.warn { "Spindler & Klatt card '$dateLine' has no parseable date, skipping" }
            return null
        }
        val weekday = parseGermanWeekdayAbbreviation(WEEKDAY.find(dateLine)?.value?.trim())
        val date = inferYearForWeekday(monthDay, weekday, clock)
        val title =
            card
                .selectFirst("h3")
                ?.textLines()
                ?.joinToString(" ")
                ?.replace(WHITESPACE, " ")
                .blankToNull()
        if (title == null) {
            logger.warn { "Spindler & Klatt night on $date has no title, skipping" }
            return null
        }
        val time = NUMERIC_DAY_MONTH.find(dateLine)?.let { TIME_RANGE.find(dateLine, it.range.last + 1) }
        val start = time?.let { clockAt(it, START_HOUR, START_MINUTE) }
        val end = time?.let { clockAt(it, END_HOUR, END_MINUTE) }
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            imageUrl = card.imgSrcAt("img.poster")?.substringBefore(WIX_TRANSFORM),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SPINDLER_KLATT.sourceIdPrefix}$date",
            ticketUrl = card.takeIf { it.tagName() == "a" }?.absUrl("href").blankToNull()
        )
    }

    /** The clock in [hourGroup] and [minuteGroup] of [match], the minutes `00` when the page prints only the hour. */
    private fun clockAt(
        match: MatchResult,
        hourGroup: Int,
        minuteGroup: Int
    ): LocalTime? {
        val hour = match.groupValues[hourGroup].toIntOrNull() ?: return null
        val minute = match.groupValues[minuteGroup].toIntOrNull() ?: 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private companion object {
        /** The weekday that opens the date line, `Sa` in `Sa · 14.11. · 22:00 Uhr`. */
        val WEEKDAY = Regex("""^\s*\p{L}{2}(?!\p{L})""")

        /** `22:00 Uhr`, or a range in hours `19–23 Uhr` or in clocks `19:00 - 23:00 Uhr`. */
        val TIME_RANGE = Regex("""(\d{1,2})(?::(\d{2}))?(?:\s*[-–]\s*(\d{1,2})(?::(\d{2}))?)?\s*Uhr""")
        const val START_HOUR = 1
        const val START_MINUTE = 2
        const val END_HOUR = 3
        const val END_MINUTE = 4

        /** Wix appends a resize transform to the media URL; the part before it is the original file. */
        const val WIX_TRANSFORM = "/v1/"
    }
}

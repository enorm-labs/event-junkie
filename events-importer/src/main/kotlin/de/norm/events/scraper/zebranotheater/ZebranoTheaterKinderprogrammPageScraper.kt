package de.norm.events.scraper.zebranotheater

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FOUR_DIGIT_CLOCK
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.forChildren
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.MonthDay
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for the Zebrano-Theater `kinderprogramm.html`. Its public performances are one table after the
 * `Öffentliche Vorstellungen` heading: weekday, `11.10.`, `16:00 Uhr`, the play with its company, the price and the
 * age (`ab 3 Jahren`). Each is marked `for-children` (EVENT_SCOPE.md §3.5) with the age as its phrase.
 *
 * The page prints no year, so the weekday picks it. The Kita and school table below lists plays to book, not dates,
 * and is not read. A row's key carries its time as well as its date, so it never takes the evening programme's key.
 */
class ZebranoTheaterKinderprogrammPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val heading = document.select("h2").firstOrNull { PUBLIC_PERFORMANCES.containsMatchIn(it.text()) }
        val table = heading?.nextElementSiblings()?.firstOrNull { it.tagName() == "table" }
        val rows = table?.select("tr:has(> td)").orEmpty()
        logger.info { "Found ${rows.size} Zebrano-Theater children's performance(s) on $baseUrl" }
        return rows.mapSkippingFailures(logger, "Zebrano-Theater children's row") { row -> parseRow(row, baseUrl) }
    }

    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val cells = row.select("> td")
        if (cells.size < MIN_CELLS) return null
        val dayMonth = DAY_MONTH.find(cells[DATE_CELL].text()) ?: return null
        val monthDay = runCatching { MonthDay.of(dayMonth.groupValues[2].toInt(), dayMonth.groupValues[1].toInt()) }.getOrNull() ?: return null
        val eventDate = inferYearForWeekday(monthDay, parseGermanWeekdayAbbreviation(cells[WEEKDAY_CELL].text()), clock)
        val startTime = parseTime(cells[TIME_CELL].text().removeSuffix(TIME_SUFFIX).trim())
        val billing = cells[BILLING_CELL]
        val link = billing.selectFirst("a[href]")
        val title = link?.text()?.replace(WHITESPACE, " ")?.blankToNull() ?: return null
        val lines =
            billing.html().split(LINE_BREAK).map {
                Jsoup
                    .parse(it)
                    .text()
                    .replace(WHITESPACE, " ")
                    .trim()
            }
        val company =
            lines
                .firstOrNull()
                ?.substringAfter(title, "")
                ?.trim(' ', '-', '–')
                ?.blankToNull()
        val age = cells[AGE_CELL].text().blankToNull()
        return ScrapedEvent(
            title = title,
            subtitle = company,
            description = lines.drop(1).joinToString(" ").blankToNull(),
            eventType = EventType.SHOW.name,
            eventDate = eventDate,
            startTime = startTime,
            sourceUrl = link.absUrl("href").blankToNull() ?: baseUrl,
            sourceId =
                "${EventSource.ZEBRANO_THEATER.sourceIdPrefix}kinder-${eventDate.format(DateTimeFormatter.BASIC_ISO_DATE)}" +
                    "-${startTime?.format(FOUR_DIGIT_CLOCK) ?: "0000"}",
            priceBoxOffice = parsePriceValue(cells[PRICE_CELL].text())
        ).forChildren(age ?: CHILDRENS_PROGRAMME)
    }

    private companion object {
        const val MIN_CELLS = 6
        const val WEEKDAY_CELL = 0
        const val DATE_CELL = 1
        const val TIME_CELL = 2
        const val BILLING_CELL = 3
        const val PRICE_CELL = 4
        const val AGE_CELL = 5
        const val TIME_SUFFIX = "Uhr"
        const val CHILDRENS_PROGRAMME = "Kinderprogramm"

        val PUBLIC_PERFORMANCES = Regex("""öffentliche\s+vorstellungen""", RegexOption.IGNORE_CASE)
        val DAY_MONTH = Regex("""(\d{1,2})\.(\d{1,2})\.""")
        val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    }
}

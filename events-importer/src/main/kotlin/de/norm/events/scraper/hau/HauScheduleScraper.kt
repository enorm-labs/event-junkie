package de.norm.events.scraper.hau

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.orderDoorsBeforeStart
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for one month of the HAU schedule (`/en/programme/schedule-tickets/`), keeping the
 * rows on [house]'s stages. Each `.month` block opens with an `October 2026` heading; each
 * `li.day` carries its day as `data-filterDate="16-10"`, and each `.item` row the time, the stage
 * (`data-venue`), the categories, the artist line (`h3`) and the work (`h4`). A row without a
 * stage is a festival or service notice. `data-date` is no midnight in any one zone, so the date
 * comes from the heading and the day.
 */
class HauScheduleScraper(
    private val house: HauHouse
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        url: String
    ): List<ScrapedEvent> =
        document.select("div.month").flatMap { month ->
            val yearMonth = parseYearMonth(month.textAt(".month-box h3"))
            if (yearMonth == null) {
                logger.warn { "HAU month block without a readable heading on $url, skipping it" }
                return@flatMap emptyList()
            }
            month.select("li.day").flatMap { day ->
                val date =
                    day
                        .attr("data-filterdate")
                        .substringBefore('-')
                        .toIntOrNull()
                        ?.let { yearMonth.atDayOrNull(it) }
                day.select(".item:has(a.event-venue)").mapSkippingFailures(logger, "HAU schedule row") { parseRow(it, date) }
            }
        }

    /** The month page after this one, from the month switcher's `a[title=11-2026]` links; null after the last published month. */
    fun nextMonthPage(document: Document): String? {
        val next = parseYearMonth(document.textAt("div.month .month-box h3"))?.plusMonths(1) ?: return null
        return document.selectFirst("a[title=${next.format(MONTH_LINK_TITLE)}][href]")?.absUrl("href").blankToNull()
    }

    private fun parseRow(
        item: Element,
        date: LocalDate?
    ): ScrapedEvent? {
        val stage = item.attrAt("a.event-venue", "data-venue")?.trim() ?: return null
        if (stage !in house.stages) return null
        val categories = item.select(".cat-box li.cat").map { it.text().trim().lowercase() }
        if (isOutOfScope(categories)) return null
        val title = item.textAt(".info-wrap > h3")
        val page = item.selectFirst("a.show-btn[href]")?.absUrl("href").blankToNull()
        if (title == null || date == null || page == null) {
            logger.warn { "HAU row without a title, date or production page on $stage ($title, $date), skipping" }
            return null
        }
        val subtitle = item.textAt(".info-wrap > h4")
        val start = parseTime(item.textAt(".floats strong"))
        val doorsText = item.select(".info-wrap > p").firstNotNullOfOrNull { DOORS.find(it.text())?.groupValues?.get(1) }
        val (doors, startTime) = orderDoorsBeforeStart(parseTime(doorsText), start)
        val type = eventType(categories, title, subtitle)
        val slug = page.trimEnd('/').substringAfterLast('/')
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = type.name,
            typeIsFallback = categories.isEmpty() && type == EventType.SHOW,
            eventDate = date,
            doorsTime = doors,
            startTime = startTime,
            room = house.stages[stage],
            sourceUrl = page,
            sourceId = "${house.eventSource.sourceIdPrefix}$slug-$date-${start?.format(SOURCE_ID_TIME).orEmpty()}",
            status = item.textAt(".tagBox .tag")?.let(::parseEventStatus) ?: EventStatus.SCHEDULED.name,
            artists = artists(type, title, subtitle)
        )
    }

    /**
     * The venue's categories decide the type (EVENT_SCOPE.md §1). A staged label wins over music, since
     * `Music, Dance` is a dance piece; a talk wins over music, since `Dialogue, Music` is a listening
     * session. A row with no category is a staged work at this theatre, typed as the fallback.
     */
    private fun eventType(
        categories: List<String>,
        title: String,
        subtitle: String?
    ): EventType =
        when {
            DJ_SET.containsMatchIn("$title ${subtitle.orEmpty()}") -> EventType.PARTY
            categories.any { it in STAGED } -> EventType.SHOW
            "dialogue" in categories -> EventType.READING
            "music" in categories -> EventType.CONCERT
            "film" in categories -> EventType.SCREENING
            "installation" in categories -> EventType.EXHIBITION
            else -> EventType.SHOW
        }

    /** The DJs of a `DJ set by A & B` night; the act of a concert. A staged work bills nothing: its line names an artist, a company or a series alike. */
    private fun artists(
        type: EventType,
        title: String,
        subtitle: String?
    ): List<ScrapedArtist> =
        when (type) {
            EventType.PARTY -> {
                listOfNotNull(subtitle, title)
                    .firstNotNullOfOrNull {
                        DJ_SET
                            .find(it)
                            ?.groupValues
                            ?.get(1)
                            ?.blankToNull()
                    }?.let(::splitSegmentOnConjunctions)
                    .orEmpty()
                    .map { ScrapedArtist(name = it, role = "DJ") }
            }

            EventType.CONCERT -> {
                buildArtistsForEventType(title, subtitle.takeUnless { it.equals("Concert", ignoreCase = true) }, type.name)
            }

            else -> {
                emptyList()
            }
        }

    private fun parseYearMonth(text: String?): YearMonth? =
        text?.replace(' ', ' ')?.blankToNull()?.let {
            try {
                YearMonth.parse(it, MONTH_HEADING)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun YearMonth.atDayOrNull(day: Int): LocalDate? = if (isValidDay(day)) atDay(day) else null

    companion object {
        private val MONTH_HEADING: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        private val MONTH_LINK_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-yyyy")
        private val SOURCE_ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
        private val DOORS = Regex("""Doors:\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
        private val DJ_SET = Regex("""\bDJ[ -]sets?\b(?:\s+by\s+(.+))?""", RegexOption.IGNORE_CASE)
        private val STAGED = setOf("performance", "dance", "theatre", "game")

        /**
         * An online piece has no stage to go to, and a workshop is taken part in (EVENT_SCOPE.md §3.2, §5).
         */
        fun isOutOfScope(categories: List<String>): Boolean = categories.any { it == "online" || it == "workshop" }
    }
}

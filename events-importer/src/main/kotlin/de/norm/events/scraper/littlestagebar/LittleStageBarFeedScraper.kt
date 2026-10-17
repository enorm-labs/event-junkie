package de.norm.events.scraper.littlestagebar

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekday
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Reads the Little Stage Bar's WordPress.com RSS feed. Each `<item>` is a blog post, and its date
 * and time exist only in the prose of `content:encoded` (`Sonnabend – 26.9.26 20:30 Uhr`,
 * `14. Februar 2026 – 20 Uhr`). A post is an event only when its text names exactly one date.
 * A year-less date needs a weekday before it. A weekly series (`jeden Freitag`), a post without a
 * readable date and a post naming several dates are skipped. The publish date is never used.
 */
class LittleStageBarFeedScraper(
    private val clock: Clock
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        xml: String,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = Jsoup.parse(xml, baseUrl, Parser.xmlParser()).select("item")
        logger.info { "Found ${items.size} post(s) in the Little Stage Bar feed" }
        return items.mapSkippingFailures(logger, "Little Stage Bar post") { parseItem(it) }
    }

    private fun parseItem(item: Element): ScrapedEvent? {
        val link = item.firstText("link") ?: return null
        val title = item.firstText("title")?.let(::cleanEventTitle) ?: return null
        val text = htmlParagraphText(item.getElementsByTag("content:encoded").first()?.text()).orEmpty()
        if (SERIES_PATTERN.containsMatchIn(text)) {
            logger.info { "Skipping Little Stage Bar post '$title': it announces a weekly series" }
            return null
        }
        val mentions = findDates(text, title).distinctBy { it.date }
        val mention = mentions.singleOrNull()
        if (mention == null) {
            logger.warn { "Skipping Little Stage Bar post '$title' ($link): ${mentions.size} dates in its text, need exactly one" }
            return null
        }
        val clock = clockNear(text, mention)
        val slug = link.trimEnd('/').substringAfterLast('/')
        return ScrapedEvent(
            title = title,
            description = text.blankToNull(),
            eventType = inferConcertVenueType(title),
            eventDate = mention.date,
            doorsTime = clock?.takeIf { it.doors }?.time,
            startTime = clock?.takeUnless { it.doors }?.time,
            imageUrl = imageUrl(item),
            sourceUrl = link,
            sourceId = "${EventSource.LITTLE_STAGE_BAR.sourceIdPrefix}$slug",
            free = detectFree(priceNote = text)
        )
    }

    /** Every date the text names, numeric (`26.9.26`) or with the month spelled out (`24.Januar 2026`). */
    private fun findDates(
        text: String,
        title: String
    ): List<DateMention> {
        val numeric =
            NUMERIC_DATE.findAll(text).mapNotNull { match ->
                val (day, month, year) = match.destructured
                val fullYear = if (year.length == 2) CENTURY + year.toInt() else year.toInt()
                dateOrNull(fullYear, month.toInt(), day.toInt())?.let { mention(text, match, it, title) }
            }
        val named =
            NAMED_DATE.findAll(text).mapNotNull { match ->
                val (day, monthName, year) = match.destructured
                val month = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(monthName, ignoreCase = true) } + 1
                namedDate(text, match, day.toInt(), month, year.toIntOrNull())?.let { mention(text, match, it, title) }
            }
        return (numeric + named).sortedBy { it.start }.toList()
    }

    /** A spelled-out date; without a year, only the weekday printed before it fixes the year. */
    private fun namedDate(
        text: String,
        match: MatchResult,
        day: Int,
        month: Int,
        year: Int?
    ): LocalDate? {
        if (year != null) return dateOrNull(year, month, day)
        val weekday = weekdayBefore(text, match.range.first) ?: return null
        val monthDay = runCatching { MonthDay.of(month, day) }.getOrNull() ?: return null
        return inferYearForWeekday(monthDay, weekday, clock, yearWindow = 1).takeIf { it.dayOfWeek == weekday }
    }

    private fun mention(
        text: String,
        match: MatchResult,
        date: LocalDate,
        title: String
    ): DateMention {
        val checked = dateCheckedAgainstWeekday(date, weekdayBefore(text, match.range.first), title)
        return DateMention(checked, match.range.first, match.range.last + 1)
    }

    /** The first clock after the date, else the last one shortly before it (`Beginn: 15 Uhr am 14.12.25`). */
    private fun clockNear(
        text: String,
        mention: DateMention
    ): PostClock? {
        val after = text.substring(mention.end, minOf(text.length, mention.end + CLOCK_WINDOW_AFTER))
        val before = text.substring(maxOf(0, mention.start - CLOCK_WINDOW_BEFORE), mention.start)
        return CLOCK.findAll(after).firstNotNullOfOrNull(::postClock) ?: CLOCK.findAll(before).mapNotNull(::postClock).lastOrNull()
    }

    private fun postClock(match: MatchResult): PostClock? {
        val minutes = match.groups["m"]?.value
        if (minutes == null && match.groups["uhr"] == null) return null
        val hour = match.groups["h"]?.value?.toInt() ?: return null
        val time = runCatching { LocalTime.of(hour, minutes?.toInt() ?: 0) }.getOrNull() ?: return null
        return PostClock(time, doors = match.groups["doors"] != null)
    }

    private fun weekdayBefore(
        text: String,
        index: Int
    ): DayOfWeek? {
        val name =
            WEEKDAY_BEFORE
                .find(text.substring(maxOf(0, index - WEEKDAY_WINDOW), index))
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return if (name == "sonnabend") DayOfWeek.SATURDAY else parseGermanWeekday(name)
    }

    /** The post's picture from the feed's `media:content`, skipping the author's Gravatar. */
    private fun imageUrl(item: Element): String? =
        item
            .getElementsByTag("media:content")
            .map { it.attr("url") }
            .firstOrNull { it.isNotBlank() && "gravatar.com" !in it }

    private fun Element.firstText(tag: String): String? = getElementsByTag(tag).first()?.text().blankToNull()

    private fun dateOrNull(
        year: Int,
        month: Int,
        day: Int
    ): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private data class DateMention(
        val date: LocalDate,
        val start: Int,
        val end: Int
    )

    private data class PostClock(
        val time: LocalTime,
        val doors: Boolean
    )

    private companion object {
        const val CENTURY = 2000
        const val CLOCK_WINDOW_AFTER = 60
        const val CLOCK_WINDOW_BEFORE = 40
        const val WEEKDAY_WINDOW = 20

        const val WEEKDAYS = "montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonnabend|sonntag"
        val SERIES_PATTERN = Regex("""(?i)\bjeden\s+(?:$WEEKDAYS)\b""")
        val WEEKDAY_BEFORE = Regex("""(?i)($WEEKDAYS)\W{0,5}$""")
        val NUMERIC_DATE = Regex("""(?<![\d.])(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})(?![\d.]\d)""")
        val NAMED_DATE =
            Regex("""(?i)(?<![\d.])(\d{1,2})\.\s*(${GERMAN_MONTH_NAMES.joinToString("|")})(?:\s+(\d{4}))?(?!\p{L})""")
        val CLOCK = Regex("""(?i)(?<doors>einlass\W{0,3})?(?<![\d.:])(?<h>\d{1,2})(?::(?<m>\d{2}))?(?!\d)(?<uhr>\s*uhr)?""")
    }
}

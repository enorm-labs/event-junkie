package de.norm.events.scraper.karakas

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.PADDED_GERMAN_DATE
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * The dated entries a Karakas page renders in its HTML: the "Comedians on Tours" cards
 * (`🗓 20.10.2026` and a link to an outside shop) and the sections that announce one night with a
 * "When: 5th November" line, which prints no year and no time. The calendar is not rendered in
 * full here, only in the page's script ([calendarScripts], [KarakasCalendarScraper]).
 */
class KarakasPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        pageUrl: String,
        eventType: EventType
    ): List<ScrapedEvent> =
        document.select(TOUR_CARD).mapSkippingFailures(logger, "Karakas tour card") { tourCard(it, pageUrl, eventType) } +
            document
                .select(DESCRIPTION)
                .filter { WHEN_LINE.containsMatchIn(it.text()) }
                .mapSkippingFailures(logger, "Karakas announcement") { announcement(it, pageUrl, eventType) }

    /**
     * The published scripts of a page that shows the calendar, the page's own last. Their URLs carry
     * a hash that changes on every publish, so they are read from the page each run.
     */
    fun calendarScripts(document: Document): List<String> =
        if (document.selectFirst(CALENDAR) == null) {
            emptyList()
        } else {
            document
                .select(PUBLISHED_SCRIPT)
                .map { it.absUrl("src") }
                .filter { it.isNotEmpty() }
                .reversed()
        }

    private fun tourCard(
        card: Element,
        pageUrl: String,
        eventType: EventType
    ): ScrapedEvent? {
        val title = card.textAt(TOUR_HEADLINE)?.let(::cleanEventTitle)
        val text = card.selectFirst(TOUR_TEXT)
        val date = text?.let { FULL_DATE.find(it.text()) }?.value?.let { LocalDate.parse(it, PADDED_GERMAN_DATE) }
        if (title.isNullOrBlank() || date == null) {
            logger.warn { "Karakas tour card '$title' has no title or date, skipping" }
            return null
        }
        return dated(title, date, pageUrl, eventType).copy(
            ticketUrl = text.selectFirst("a[href]")?.absUrl("href")?.takeIf { it.isNotEmpty() },
            imageUrl = card.selectFirst("img[data-aid^=ABOUT_IMAGE_RENDERED]")?.let { image(it.absUrl("src")) }
        )
    }

    private fun announcement(
        description: Element,
        pageUrl: String,
        eventType: EventType
    ): ScrapedEvent? {
        val section = description.closest("section") ?: return null
        val title = section.textAt(SECTION_TITLE)?.let(::cleanEventTitle)
        val day = WHEN_LINE.find(description.text())?.destructured
        val monthDay = day?.let { (dayOfMonth, month) -> runCatching { MonthDay.parse("$month $dayOfMonth", MONTH_DAY) }.getOrNull() }
        if (title.isNullOrBlank() || monthDay == null) {
            logger.warn { "Karakas announcement '$title' has no title or readable date, skipping" }
            return null
        }
        val lines = description.select("p").map { it.text().trim() }.filter { it.isNotEmpty() && !WHEN_LINE.containsMatchIn(it) }
        return dated(title, inferYearForWeekday(monthDay, null, clock), pageUrl, eventType).copy(
            description = lines.joinToString("\n").ifBlank { null },
            ticketUrl = section.selectFirst("a[data-aid=CTA_BUTTON_RENDERED]")?.absUrl("href")?.takeIf { it.isNotEmpty() },
            imageUrl = section.selectFirst("img[data-srclazy]")?.let { image(it.absUrl("data-srclazy")) }
        )
    }

    private fun dated(
        title: String,
        date: LocalDate,
        pageUrl: String,
        eventType: EventType
    ) = ScrapedEvent(
        title = title,
        eventType = eventType.name,
        eventDate = date,
        sourceUrl = pageUrl,
        sourceId = "${EventSource.KARAKAS.sourceIdPrefix}$date-${SlugGenerator.slugify(title)}",
        soldOut = hasSoldOutMarker(title)
    )

    /** The full-size image: the part after `/:/` is a resize instruction. */
    private fun image(url: String): String? = url.substringBefore("/:/").takeIf { it.startsWith("http") }

    private companion object {
        const val TOUR_CARD = "[data-ux=ContentCard]:has([data-aid^=ABOUT_HEADLINE_RENDERED])"
        const val TOUR_HEADLINE = "[data-aid^=ABOUT_HEADLINE_RENDERED]"
        const val TOUR_TEXT = "[data-aid^=ABOUT_DESCRIPTION_RENDERED]"
        const val DESCRIPTION = "[data-aid=DESCRIPTION_TEXT]"
        const val SECTION_TITLE = "[data-aid=SECTION_TITLE_RENDERED]"
        const val CALENDAR = "[data-aid^=CALENDAR_]"
        const val PUBLISHED_SCRIPT = "script[src*=/gpub/][src$=/script.js]"
        val FULL_DATE = Regex("""\b\d{2}\.\d{2}\.\d{4}\b""")
        val WHEN_LINE = Regex("""\bWhen:\s*(\d{1,2})(?:st|nd|rd|th)?\.?\s+(\p{L}+)""", RegexOption.IGNORE_CASE)
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM d").toFormatter(Locale.ENGLISH)
    }
}

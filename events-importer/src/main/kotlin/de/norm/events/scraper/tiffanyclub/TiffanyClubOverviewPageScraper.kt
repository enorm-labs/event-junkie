package de.norm.events.scraper.tiffanyclub

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for Tiffany Club's `/upcoming-events/` Elementor loop.
 *
 * Each `.e-loop-item` prints weekday, day and month without a year, a "22:00 Uhr" start, a
 * title, an optional second line, a poster and a `TICKETS` button. The year comes from the
 * weekday ([inferYearForWeekday]), not the `dd-MM-yyyy` slug prefix, which one post lacks. The
 * site names no category, so a night is typed by its own words and is otherwise a `PARTY`.
 */
class TiffanyClubOverviewPageScraper(
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".e-loop-item.type-event")
        logger.info { "Found ${items.size} event item(s) on the Tiffany Club listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip one malformed item without aborting the import
        return items.mapNotNull { item ->
            try {
                parseItem(item, sourceUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse a Tiffany Club event item, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // One guard clause per thing an item can withhold reads better than nesting
    private fun parseItem(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val href = item.attrAt("a[href*=$EVENT_PATH]", "href") ?: error("No event link found")
        val card = item.selectFirst("a[href*=$EVENT_PATH]:has(.eventdivider)") ?: error("No date card found")
        val lines =
            card
                .select("h5")
                .filter { it.closest(".eventdivider") == null }
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
        val title = lines.firstOrNull() ?: error("No title found")
        // A booked-out night and a speed-dating evening are listed, but neither is a show to go and see.
        if (NOT_PROGRAMME.containsMatchIn(title)) return null

        val eventDate = parseDate(card)
        if (eventDate == null) {
            logger.warn { "No parseable date for Tiffany Club event '$title', skipping" }
            return null
        }
        val subtitle = lines.getOrNull(1)
        val eventType = classify(title, subtitle)
        val url = resolveUrl(sourceUrl, href)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            startTime =
                parseTime(
                    card
                        .selectFirst(".event-time-line")
                        ?.text()
                        ?.removeSuffix(TIME_SUFFIX)
                        ?.trim()
                ),
            imageUrl = item.imgSrcAt("img"),
            sourceUrl = url,
            sourceId = "${EventSource.TIFFANY_CLUB.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH)}",
            ticketUrl = ticketUrl(item),
            // A show here is a comedy brand, not a performer, so only a concert names its act.
            artists = if (eventType == EventType.CONCERT.name) buildArtistsForEventType(title, null, eventType) else emptyList()
        )
    }

    /** The date card's weekday, day and month; the time line is the card's fourth heading. */
    private fun parseDate(card: Element): LocalDate? {
        val (weekday, day, month) =
            card
                .select(".eventdivider :is(h2, h5)")
                .filter { it.closest(".event-time-line") == null }
                .map { it.text().trim() }
                .takeIf { it.size >= DATE_PARTS } ?: return null
        return try {
            inferYearForWeekday(MonthDay.parse("$day $month", DAY_MONTH_FORMATTER), parseGermanWeekday(weekday), clock)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The `TICKETS` button; the guest-list and table buttons beside it open forms. */
    private fun ticketUrl(item: Element): String? =
        item
            .select("a.elementor-button[href]")
            .firstOrNull { it.text().trim().equals(TICKETS_LABEL, ignoreCase = true) }
            ?.absUrl("href")
            ?.takeIf { it.startsWith("http") }

    /** The venue's own format words first, then the shared cues; a club night is the default. */
    private fun classify(
        title: String,
        subtitle: String?
    ): String = inferVenueFormatType(title, subtitle, VENUE_FORMATS).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name

    private companion object {
        const val EVENT_PATH = "/event/"
        const val TIME_SUFFIX = "Uhr"
        const val TICKETS_LABEL = "TICKETS"
        const val DATE_PARTS = 3

        val DAY_MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.GERMAN)

        val VENUE_FORMATS = linkedMapOf("comedy" to EventType.SHOW.name, "concert" to EventType.CONCERT.name, "konzert" to EventType.CONCERT.name)

        val NOT_PROGRAMME = Regex("""^(private event|matching night)$""", RegexOption.IGNORE_CASE)
    }
}

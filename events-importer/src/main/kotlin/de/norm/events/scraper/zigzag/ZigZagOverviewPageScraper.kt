package de.norm.events.scraper.zigzag

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for Zig Zag's `/programmneu` page: Squarespace summary blocks, one per month plus a
 * featured one, listing the events collection under `/program-mai/`.
 *
 * Each item prints the title, an English date with its year (`October 1, 2026`), a thumbnail and an
 * excerpt whose first paragraph is the style in brackets (`(Soul, Jazz)`). The collection carries
 * both venues: items titled `ZIG ZAG HALL: …` are the hall's, the rest the club's. [eventSource]
 * keeps one venue's items, and the hall's lose the prefix. The featured block repeats a night and
 * can still show yesterday's, so a night is kept once and a past one is dropped.
 */
class ZigZagOverviewPageScraper(
    private val eventSource: EventSource,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val hall = eventSource == EventSource.ZIG_ZAG_HALL
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val items = document.select(".summary-item-record-type-event")
        logger.info { "Found ${items.size} event item(s) on the Zig Zag programme" }
        val today = LocalDate.now(clock)

        return items
            .mapSkippingFailures(logger, "a Zig Zag event item") { item ->
                parseItem(item, sourceUrl)
            }.filterNot { it.eventDate.isBefore(today) }
            .distinctBy { it.sourceId }
    }

    @Suppress("ReturnCount") // One guard clause per thing an item can withhold reads better than nesting
    private fun parseItem(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst("a.summary-title-link[href]") ?: error("No event link found")
        val listed = link.text().trim().ifEmpty { error("No title found") }
        if (listed.startsWith(HALL_PREFIX, ignoreCase = true) != hall) return null
        val title = if (hall) listed.substring(HALL_PREFIX.length).trim() else listed

        val eventDate = parseDate(item.textAt("time.summary-metadata-item--date"))
        if (eventDate == null) {
            logger.warn { "No parseable date for Zig Zag event '$title', skipping" }
            return null
        }
        val url = link.absUrl("href").ifEmpty { error("No absolute event URL for '$title' on $sourceUrl") }
        return ScrapedEvent(
            title = title,
            eventType = EventType.CONCERT.name,
            eventDate = eventDate,
            imageUrl = item.attrAt("img.summary-thumbnail-image", "data-src")?.takeIf { it.startsWith("http") },
            sourceUrl = url,
            sourceId = "${eventSource.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH)}",
            genre =
                item
                    .textAt(".summary-excerpt p")
                    ?.let { STYLE.find(it.trim()) }
                    ?.groupValues
                    ?.get(1)
                    ?.trim(),
            artists = if (NO_ACT.containsMatchIn(title)) emptyList() else buildArtistsForEventType(actOf(title), null, EventType.CONCERT.name)
        )
    }

    /**
     * The act before a programme name: `Mirna Bogdanovic - Glimmerence`, `Ingrid Arthur & Band: Too hot to handle`,
     * `Jason Moran Plays Duke Ellington`.
     */
    private fun actOf(title: String): String = title.split(PROGRAMME_SEPARATOR, limit = 2).first().trim()

    private fun parseDate(text: String?): LocalDate? =
        try {
            text?.let { LocalDate.parse(it.trim(), DATE_FORMATTER) }
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        const val EVENT_PATH = "/program-mai/"
        const val HALL_PREFIX = "ZIG ZAG HALL:"

        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

        /** A paragraph of its own, or one the blurb runs straight on from: `(Jazz)Tradition trifft …`. */
        val STYLE = Regex("""^\(([^()]+)\)""")
        val PROGRAMME_SEPARATOR = Regex("""\s+-\s+|:\s+|\s+(?:plays|celebrates?)\s+""", RegexOption.IGNORE_CASE)

        /** The weekly jam and the tribute nights name a format or an honoree, not the band on stage. */
        val NO_ACT = Regex("""jam session|tribute""", RegexOption.IGNORE_CASE)
    }
}

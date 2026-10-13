package de.norm.events.scraper.frachtkante

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for the `Veranstaltungen` section of the Frachtkante homepage, one `article` per event.
 *
 * The date comes from `time[datetime]`; the printed line beside it carries the clock range or `Ganztägig`
 * and, for a multi-day event, the last day. The section is rendered twice, so rows are deduplicated. The
 * page has no link per event, so the `sourceId` is the date plus the title.
 */
class FrachtkanteOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("article:has(> time[datetime]):has(h3)")
        logger.info { "Found ${articles.size} Frachtkante article(s), the section rendered twice" }
        return articles
            .mapSkippingFailures(logger, "Frachtkante event") { parseArticle(it, baseUrl) }
            .distinctBy { it.sourceId }
    }

    @Suppress("ReturnCount") // Guard clauses for the required title and date are clearer than nesting.
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = article.textAt("h3")
        if (title.isNullOrBlank()) {
            logger.warn { "Frachtkante event without a title, skipping" }
            return null
        }
        val eventDate = article.selectFirst("> time[datetime]")?.attr("datetime")?.let(::parseIsoDate)
        if (eventDate == null) {
            logger.warn { "Could not date Frachtkante event '$title', skipping" }
            return null
        }
        val printed = article.select("span").filter { it.closest("time, .festival-styled") == null }.map { it.text() }
        val clock = printed.firstNotNullOfOrNull { CLOCK_RANGE.find(it) }
        val startTime = clock?.let { parseTime(it.groupValues[1]) }
        val clockEnd = clock?.let { parseTime(it.groupValues[2]) }
        val lastDay = printed.firstNotNullOfOrNull { DAY_RANGE.find(it) }?.let { parseLongDate(it.groupValues[1]) }
        val endDate = lastDay ?: clockEnd?.let { endOn(eventDate, startTime, it) }
        val body = article.selectFirst(".festival-styled")

        return ScrapedEvent(
            title = title,
            description = body?.let(::description),
            eventType = classify(title),
            eventDate = eventDate,
            startTime = startTime,
            endDate = endDate,
            endTime = clockEnd?.takeIf { endDate != null },
            sourceUrl = baseUrl,
            sourceId = "${EventSource.FRACHTKANTE.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = body?.select("a[href]")?.map { it.absUrl("href") }?.firstOrNull { it.startsWith("http") }
        )
    }

    /** The paragraphs under the title, without a line that only points at the ticket shop. */
    private fun description(body: Element): String? =
        body
            .select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !TICKET_LINE.matches(it) }
            .joinToString("\n")
            .ifBlank { null }

    private fun classify(title: String): String = inferVenueFormatType(title, null, VENUE_FORMATS)

    private fun parseLongDate(text: String): LocalDate? =
        try {
            LocalDate.parse(text.trim(), LONG_DATE)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        /** `12:00 - 23:00 Uhr`. */
        val CLOCK_RANGE = Regex("""(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})""")

        /** The last day of `28. November 2026 - 29. November 2026`. */
        val DAY_RANGE = Regex("""[-–]\s*(\d{1,2}\.\s*\p{L}+\s+\d{4})\s*$""")

        val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

        /** `tickets via https://…`, the line that only carries the ticket link. */
        val TICKET_LINE = Regex("""^tickets?\b[^.]{0,20}https?://\S+$""", RegexOption.IGNORE_CASE)

        /**
         * The house's own format words; a market falls through to the shared `markt` cue. A khisdapaze
         * open air is a DJ day party.
         */
        val VENUE_FORMATS: Map<String, String> =
            linkedMapOf(
                "open air" to EventType.PARTY.name,
                "konzert" to EventType.CONCERT.name,
                "concert" to EventType.CONCERT.name,
                "theater" to EventType.SHOW.name
            )
    }
}

package de.norm.events.scraper.yaam

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode

/**
 * Pure JSON parser for YAAM's **The Events Calendar** REST API; the page shape and the field readers are [parseTecPage]'s.
 *
 * - **The text is in `excerpt`.** `description` holds only the flyer, as a 225×300 thumbnail, and no event sets a
 * featured `image`, so the flyer's full-size file (the URL without its `-225x300` suffix) is the image.
 * - **A title ends on the date** (`Noanda Soul Jam 10.11.2026`), which goes. The editors type it by hand and it can
 * disagree with `start_date`, which is the one read.
 * - **No category is set**, so the title types the night; a jam session is a concert.
 */
class YaamApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.YAAM, ::toScrapedEvent)

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let { cleanEventTitle(it.replace(TRAILING_DATE, "")) }
        if (title.isNullOrBlank()) {
            logger.warn { "YAAM event '$slug' has no title, skipping" }
            return null
        }
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val eventType = inferVenueFormatType(title, null, VENUE_FORMATS)

        return ScrapedEvent(
            title = title,
            description = htmlParagraphText(event.stringOrNull("excerpt")) ?: event.tecDescription(),
            eventType = eventType,
            typeIsFallback = eventType == EventType.OTHER.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl() ?: flyerUrl(event),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = "${EventSource.YAAM.sourceIdPrefix}$slug",
            ticketUrl = event.stringOrNull("website")
        )
    }

    /** The full-size file of the first image in `description`. */
    private fun flyerUrl(event: JsonNode): String? =
        event
            .stringOrNull("description")
            ?.let { Jsoup.parseBodyFragment(it).selectFirst("img[src^=http]")?.attr("src") }
            ?.replace(SIZE_SUFFIX, "")

    private companion object {
        val TRAILING_DATE = Regex("""\s+\d{1,2}\.\d{1,2}\.(?:\d{2,4})?\s*$""")
        val SIZE_SUFFIX = Regex("""-\d+x\d+(?=\.\w+$)""")
        val VENUE_FORMATS = linkedMapOf("jam" to EventType.CONCERT.name)
    }
}

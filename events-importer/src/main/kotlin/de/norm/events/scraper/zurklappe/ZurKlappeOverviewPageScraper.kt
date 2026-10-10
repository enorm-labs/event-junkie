package de.norm.events.scraper.zurklappe

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.endOn
import de.norm.events.scraper.flightStringOrNull
import de.norm.events.scraper.jsonArrayAt
import de.norm.events.scraper.nextFlightPayload
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private const val EVENTS_KEY = "events"

/** What the page renders in place of the list when the venue has no dates; the payload then has no [EVENTS_KEY]. */
private const val EMPTY_STATE_TEXT = "No upcoming events."

/**
 * Pure parser for Zur Klappe's programme, read from the Next.js flight payload of `/events`. The
 * cards print `SAT, 03 OCT` with no year, while the payload holds `date` as `DD.MM.YYYY`, the
 * times, the DJs and the RA link — so the payload is the source (ADR-007 §"Prefer a JSON / API
 * Source"). The first `events` array is the upcoming list; the past list renders from other props.
 */
class ZurKlappeOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = jsonArrayAt(nextFlightPayload(document), EVENTS_KEY)
        if (events == null) {
            if (document.select("p").any { it.text() == EMPTY_STATE_TEXT }) {
                logger.info { "Zur Klappe lists no upcoming events" }
            } else {
                logger.warn { "No events array in Zur Klappe's flight payload" }
            }
            return emptyList()
        }
        return jsonMapper.readTree(events).mapNotNull { toScrapedEvent(it, baseUrl) }
    }

    @Suppress("ReturnCount") // One guard clause per thing the payload can withhold reads better than nesting
    private fun toScrapedEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val slug = node.flightStringOrNull("slug") ?: return null
        // A password-protected night is on the list but not open to the public.
        if (node.path("isPrivate").asBoolean(false)) return skip(slug, "private event")
        val title = node.flightStringOrNull("title") ?: return skip(slug, "no title")
        val date = parseGermanDate(node.flightStringOrNull("date")) ?: return skip(slug, "no usable date")
        val start = parseTime(node.flightStringOrNull("time"))
        val end = parseTime(node.flightStringOrNull("endTime"))

        return ScrapedEvent(
            title = title,
            description = node.flightStringOrNull("description"),
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            imageUrl = node.flightStringOrNull("coverImage")?.takeIf { it.startsWith("http") },
            sourceUrl = resolveUrl(baseUrl, "/events/$slug"),
            sourceId = "${EventSource.ZUR_KLAPPE.sourceIdPrefix}$slug",
            ticketUrl = node.flightStringOrNull("raLink")?.takeIf { it.startsWith("http") },
            artists = node.path("djs").mapNotNull { dj -> dj.stringOrNull("name")?.let { ScrapedArtist(it) } }
        )
    }

    private fun skip(
        slug: String,
        reason: String
    ): ScrapedEvent? {
        logger.warn { "Skipping Zur Klappe event $slug: $reason" }
        return null
    }
}

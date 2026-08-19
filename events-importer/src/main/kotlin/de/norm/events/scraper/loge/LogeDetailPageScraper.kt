package de.norm.events.scraper.loge

import de.norm.events.event.EventStatus
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Pure parser for Loge event detail pages (`/event-details/<slug>`).
 *
 * Each detail page carries a `<script type="application/ld+json">` schema.org
 * `Event` block — the most stable source on the page (ADR-007 §"Selector
 * Strategy" priority 1). The detail page is the **primary** source for the
 * ticket price (`offers`) and confirms the title, Berlin-local date/start time
 * (from the offset-aware `startDate`), and scheduling status. It does not render
 * the artist roster, so `artists` are left for the overview page to supply via
 * [LogeWebsiteImporter.fillGapsFromOverview].
 *
 * @see LogeOverviewPageScraper for overview parsing (discovery, artists, fallback).
 * @see LogeWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.loge-berlin.org/event-details/estamoe-daloy-furie">Example detail page</a>
 */
class LogeDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page
     * has no parseable schema.org `Event` JSON-LD or it lacks a title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   derive the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing JSON-LD and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val event = parseEventNode(document)
        if (event == null) {
            logger.warn { "Detail page at $sourceUrl has no schema.org Event JSON-LD, skipping" }
            return null
        }
        val title = event.stringOrNull("name")
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no event name, skipping" }
            return null
        }

        val startDate = event.stringOrNull("startDate")
        return ScrapedEvent(
            title = title,
            // Detail pages always carry the real date; sentinel only if absent
            // (then the overview value is used via fillGapsFromOverview).
            eventDate = startDate?.let { parseIsoDate(it) } ?: UNRESOLVED_EVENT_DATE,
            startTime = startDate?.let { parseJsonLdTime(it) },
            imageUrl = event.path("image").stringOrNull("url"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.LOGE.sourceIdPrefix}${extractEventSlug(sourceUrl, "/event-details/")}",
            pricePresale = parsePresalePrice(event.path("offers")),
            status = mapSchemaEventStatus(event.stringOrNull("eventStatus"))
        )
    }

    /**
     * Parses the JSON-LD block and returns its schema.org `Event` object node, or
     * `null` if there is no such block or it cannot be parsed. Defensively unwraps
     * an array or `@graph` container and picks the first object.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed block must degrade to null, never abort the import
        "ReturnCount" // Guard clauses for the missing/unparseable block are clearer than nesting
    )
    private fun parseEventNode(document: Document): JsonNode? {
        val jsonLd =
            document
                .select("script[type=application/ld+json]")
                .map { it.data() }
                .firstOrNull { it.contains("\"startDate\"") }
                ?: return null
        val root =
            try {
                jsonMapper.readTree(jsonLd)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Loge JSON-LD block" }
                return null
            }
        val candidates =
            when {
                root.isArray -> root.toList()
                root.path("@graph").isArray -> root.path("@graph").toList()
                else -> listOf(root)
            }
        return candidates.firstOrNull { it.isObject && it.stringOrNull("startDate") != null }
    }

    /**
     * Reads the presale price from the schema.org `offers` node. Loge sells
     * tickets on-site through Wix, so a single ticket type is normal; the
     * [AggregateOffer][https://schema.org/AggregateOffer]'s `lowPrice` (the
     * cheapest ticket, gross of the service fee) is used, falling back to a plain
     * offer's `price` or the first nested `offers[].price`. Returns `null` for a
     * free or price-less event.
     */
    private fun parsePresalePrice(offers: JsonNode): BigDecimal? {
        val candidates =
            listOf(
                offers.stringOrNull("lowPrice"),
                offers.stringOrNull("price"),
                offers.path("offers").firstOrNull()?.stringOrNull("price")
            )
        return candidates.firstNotNullOfOrNull { it?.toBigDecimalOrNull() }
    }

    /**
     * Parses the `HH:mm` start time from a schema.org `startDate` such as
     * `"2026-07-17T19:00:00+02:00"`. The offset already expresses Berlin-local
     * time, so only the leading `HH:mm` of the time part is read (the shared
     * [parseIsoTime][de.norm.events.scraper.parseIsoTime] rejects the trailing
     * seconds/offset). Returns `null` when there is no time component.
     */
    private fun parseJsonLdTime(startDate: String): LocalTime? {
        val timePart = startDate.substringAfter("T", "").take(HH_MM_LENGTH)
        return parseTime(timePart.takeIf { it.isNotBlank() })
    }
}

/**
 * Maps a schema.org `eventStatus` URL (e.g. `https://schema.org/EventCancelled`)
 * to a domain [EventStatus] name. Rescheduled events (a new date) map to
 * `POSTPONED`; an unknown or absent value defaults to `SCHEDULED`.
 */
internal fun mapSchemaEventStatus(url: String?): String =
    when (url?.substringAfterLast('/')?.trim()) {
        "EventCancelled" -> EventStatus.CANCELLED.name
        "EventPostponed", "EventRescheduled" -> EventStatus.POSTPONED.name
        else -> EventStatus.SCHEDULED.name
    }

private const val HH_MM_LENGTH = 5

package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HH_MM_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseSchemaEventStatus
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal

/**
 * Pure parser for Tempodrom's programme, read from the schema.org JSON-LD its listing page embeds.
 *
 * `/programm-und-tickets/` carries the venue's **entire** programme as one
 * `<script type="application/ld+json">` array of `Event` objects — 145 at the time of writing —
 * each with `startDate`, `doorTime`, `image`, `description`, `eventStatus` and an `offers` block.
 * The rendered cards add nothing the JSON-LD lacks, so the structured data is the source and the
 * markup is never selected against (ADR-007 §"Selector Strategy" priority 1).
 *
 * Two of the venue's own fields are deliberately not taken at face value. `performer.name` is
 * always a copy of the event `name` rather than an act, so it is ignored and artists are derived
 * from the title as for any other concert hall. And `location.name` is always "Tempodrom Berlin",
 * so the house's Große / Kleine Arena split — which appears nowhere in the listing — is not
 * represented.
 *
 * The JSON-LD strings are HTML-escaped and script content is not decoded by Jsoup, so `name` and
 * `description` are run through [decodeHtmlEntities] before anything else touches them — see that function
 * for why decoding late would be too late.
 *
 * @see TempodromWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.tempodrom.de/programm-und-tickets/">Tempodrom programme</a>
 */
class TempodromOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    /**
     * Parses every event from the listing page's JSON-LD.
     *
     * @return a list of [ScrapedEvent] instances; empty when the page carries no parseable
     *   schema.org `Event` data.
     */
    fun scrape(document: Document): List<ScrapedEvent> {
        val nodes = document.select("script[type=application/ld+json]").flatMap { parseEvents(it.data()) }
        logger.info { "Found ${nodes.size} schema.org Event object(s) on the Tempodrom programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed objects without aborting the whole import
        return nodes.mapNotNull { node ->
            try {
                parseEvent(node)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Tempodrom event, skipping" }
                null
            }
        }
    }

    /** Reads the `Event` objects out of one JSON-LD block, which may hold a single object or an array. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a malformed block degrades to "no events", never a failed import
    private fun parseEvents(json: String): List<JsonNode> =
        try {
            val root = jsonMapper.readTree(json)
            (if (root.isArray) root.toList() else listOf(root)).filter { it.path("@type").asString("").contains(EVENT_TYPE) }
        } catch (e: Exception) {
            logger.warn(e) { "Tempodrom JSON-LD block is not parseable, skipping it" }
            emptyList()
        }

    /** Maps one schema.org `Event` object onto a [ScrapedEvent], or `null` when it has no name or date. */
    @Suppress("ReturnCount") // Guard clauses for the required name/date are clearer than nesting
    private fun parseEvent(event: JsonNode): ScrapedEvent? {
        val title =
            event
                .path("name")
                .asString(null)
                ?.let(::decodeHtmlEntities)
                ?.takeIf { it.isNotBlank() }
                ?.let(::cleanEventTitle) ?: return null
        val startedAt = event.path("startDate").asString("").takeIf { it.isNotBlank() } ?: return null
        val eventDate = parseIsoDate(startedAt) ?: return null

        val url = event.path("url").asString("").trim()
        val subtitle =
            event
                .path("description")
                .asString(null)
                ?.let(::decodeHtmlEntities)
                ?.takeIf { it.isNotBlank() }
        val eventType = inferConcertVenueType(title)
        val offers = event.path("offers")
        val (presale, priceNote) = parsePrices(offers)

        return ScrapedEvent(
            title = title,
            // The venue's `description` is the tour or edition name ("The Ca$ino Tour", "Jungle
            // Vibes Edition"), not a blurb — it belongs in the subtitle.
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            // `doorTime` is a full timestamp of its own; only its clock part is wanted.
            doorsTime = parseClockTime(event.path("doorTime").asString("")),
            // A multi-day run publishes a date-only `startDate`, so it simply has no start time.
            startTime = parseClockTime(startedAt),
            imageUrl =
                event
                    .path("image")
                    .firstOrNull()
                    ?.asString(null)
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = url,
            sourceId = "${EventSource.TEMPODROM.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH_PREFIX)}",
            ticketUrl = offers.path("url").asString(null)?.takeIf { it.startsWith("http") && it != url },
            pricePresale = presale,
            priceNote = priceNote,
            soldOut = offers.path("availability").asString("").endsWith(SOLD_OUT_TERM),
            status = parseSchemaEventStatus(event.path("eventStatus").asString(null)),
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /**
     * Reads the cheapest ticket price and, when the offer spans a range, a note recording it.
     *
     * `offers` publishes `price` and `lowPrice` identically (the cheapest tier) plus a `highPrice`;
     * 68 of the 86 priced events span a range, so storing the low price alone would understate what
     * most seats cost — the range is kept verbatim in the note. An event with no `offers` at all,
     * or with only an availability, yields neither.
     */
    private fun parsePrices(offers: JsonNode): Pair<BigDecimal?, String?> {
        val low = parseDecimal(offers.path("lowPrice").asString(null) ?: offers.path("price").asString(null))
        val high = parseDecimal(offers.path("highPrice").asString(null))
        val currency = offers.path("priceCurrency").asString(DEFAULT_CURRENCY)
        val note = if (low != null && high != null && high > low) "$low – $high $currency" else null
        return low to note
    }

    /**
     * Parses a JSON-LD money value such as `"65.00"`.
     *
     * Deliberately not [parsePriceValue][de.norm.events.scraper.parsePriceValue]: that reads a
     * *rendered* price off a page and requires the `€` sign this machine-readable field does not
     * carry.
     */
    private fun parseDecimal(value: String?): BigDecimal? = value?.trim()?.takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /**
     * Reads the clock part of a JSON-LD timestamp such as `2026-09-01T20:30:00`.
     *
     * Deliberately not [parseIsoTime][de.norm.events.scraper.parseIsoTime]: that expects the bare
     * `HH:mm` most venues render, and returns null for the seconds this field carries. A date-only
     * value (which a multi-day run publishes) yields no time at all.
     */
    private fun parseClockTime(timestamp: String): java.time.LocalTime? =
        parseTime(timestamp.substringAfter('T', "").takeIf { it.isNotBlank() }?.take(HH_MM_LENGTH))

    private companion object {
        /** The schema.org type this parser reads; the page also emits other JSON-LD kinds. */
        const val EVENT_TYPE = "Event"

        /** Path prefix of a Tempodrom event permalink, stripped to obtain the slug identity. */
        const val EVENT_PATH_PREFIX = "/event/"

        /** The trailing term of `https://schema.org/SoldOut`. */
        const val SOLD_OUT_TERM = "SoldOut"

        /** Currency assumed when an offer omits one; every Tempodrom offer states EUR. */
        const val DEFAULT_CURRENCY = "EUR"
    }
}

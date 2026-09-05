package de.norm.events.scraper.hole44

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Pure HTML parser for Hole 44 Berlin event detail pages (`/event/<date-slug>/`).
 *
 * Each detail page carries a `<script type="application/ld+json">` schema.org
 * `Event` block — the most stable source on the page (ADR-007 §"Selector Strategy"
 * priority 1) — used for the description and image. The `single_event_header`
 * markup adds the fields the overview lacks: the promoter (`.event-promoter`), the
 * doors time (`Einlass` in the `.details` list), the support line
 * (`.single-event-support`), the ticket-shop button and the on-page image.
 *
 * The date, start time, genre, and status are all present here too and are read
 * directly, so a successful detail fetch yields a complete event; the overview
 * only fills gaps (and stands in entirely when the detail fetch fails) via
 * [Hole44WebsiteImporter.fillGapsFromOverview].
 *
 * @see Hole44OverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see Hole44WebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://hole-berlin.de/event/2026-08-02-municipal-waste/">Example detail page</a>
 */
class Hole44DetailPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page has
     * no event title (an unexpected structure).
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive
     *   the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()
        val jsonLd = parseEventNode(document)

        val title = content.textAt("h4.single-event-title") ?: jsonLd?.stringOrNull("name")
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val slug = extractEventSlug(sourceUrl, "/event/")
        val support = content.textAt("h5.single-event-support")
        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            subtitle = support,
            description = jsonLd?.stringOrNull("description"),
            eventType = eventType,
            // Prefer the structured startDate, then the slug's ISO prefix, then the German `.details` date.
            eventDate =
                jsonLd?.stringOrNull("startDate")?.let { parseIsoDate(it) }
                    ?: parseIsoDate(slug.take(ISO_DATE_LENGTH))
                    ?: parseGermanDate(detailValue(content, "Datum"))
                    ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(detailValue(content, "Einlass")),
            startTime = parseTime(detailValue(content, "Start")),
            imageUrl = jsonLd?.stringOrNull("image") ?: content.hrefAt("a.event-image"),
            // The "Tickets" button links straight to the shop (Eventim); the JSON-LD carries no offer (#1140).
            ticketUrl = content.hrefAt("a.button.ticket"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.HOLE44.sourceIdPrefix}$slug",
            genre = parseGenres(content),
            status = parseEventStatus(content.textAt(".single_event_header span.changes").orEmpty()),
            promoters = parsePromoter(content),
            artists = buildArtistsForEventType(title, support, eventType)
        )
    }

    /**
     * Parses the JSON-LD blocks and returns the schema.org `Event` object node, or
     * `null` if there is no such block or none can be parsed. Each block is parsed and
     * matched on its **decoded** `@type` (not a raw-string search), so the Yoast SEO
     * `@graph` block is skipped and detection is robust to the JSON's whitespace/format.
     */
    @Suppress("TooGenericExceptionCaught") // A malformed block must degrade to null, never abort the import
    private fun parseEventNode(document: Document): JsonNode? =
        document
            .select("script[type=application/ld+json]")
            .map { it.data() }
            .firstNotNullOfOrNull { json ->
                try {
                    jsonMapper.readTree(json).takeIf { it.stringOrNull("@type") == "Event" }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Hole 44 JSON-LD block" }
                    null
                }
            }
}

/**
 * Reads the `<span>` value of the `.details` list row whose label text contains
 * [label] (e.g. `"Einlass"` → `"19:00"`, `"Datum"` → `"02.08.2026"`), or `null`
 * when no such row exists. The label is the list item's own text; the value is its
 * trailing `<span>`.
 */
private fun detailValue(
    content: Element,
    label: String
): String? =
    content
        .select(".details li")
        .firstOrNull { it.ownText().contains(label, ignoreCase = true) }
        ?.textAt("span")

/** Trailing "presents" / "präsentiert" credit stripped from a `.event-promoter` label to leave the promoter name. */
private val PROMOTER_CREDIT_SUFFIX = Regex("""\s*(?:presents?|präsentiert|pres\.)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Extracts the promoter from the `.event-promoter` label (e.g. "Trinity Music
 * presents" → "Trinity Music"), or an empty list when none is shown. The trailing
 * "presents"/"präsentiert" credit is stripped.
 */
private fun parsePromoter(content: Element): List<String> {
    val raw = content.textAt(".event-promoter") ?: return emptyList()
    val name = raw.replace(PROMOTER_CREDIT_SUFFIX, "").trim()
    return listOfNotNull(name.takeIf { it.isNotBlank() })
}

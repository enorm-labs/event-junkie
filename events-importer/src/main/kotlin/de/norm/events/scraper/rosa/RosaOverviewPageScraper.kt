package de.norm.events.scraper.rosa

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.jsonArrayAt
import de.norm.events.scraper.nextFlightPayload
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Where the flyer assets are served from; the reference names the file within it. */
private const val SANITY_IMAGE_BASE = "https://cdn.sanity.io/images/m0p64e3g/production"

/** A Sanity asset reference: `image-<hash>-<width>x<height>-<extension>`. */
private val SANITY_ASSET_REF = Regex("""^image-([0-9a-f]+)-(\d+x\d+)-(\w+)$""")

private const val EVENTS_KEY = "events"

/**
 * Pure parser for ROSA's programme, read from the Next.js flight payload of `/dates`. The
 * markup states each date as a column of animated digits with no `time[datetime]`, while the
 * payload holds an ISO `date`, a `time` range, the title, the RA ticket link and the flyer's
 * asset reference — so the payload is the source (ADR-007 §"Prefer a JSON / API Source").
 *
 * @see RosaWebsiteImporter for the fetch, which carries the age-gate cookie.
 */
class RosaOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = jsonArrayAt(nextFlightPayload(document), EVENTS_KEY)
        if (events == null) {
            logger.warn { "No events array in ROSA's flight payload" }
            return emptyList()
        }
        return jsonMapper.readTree(events).mapNotNull { toScrapedEvent(it, baseUrl) }
    }

    @Suppress("ReturnCount") // One guard clause per thing the payload can withhold reads better than nesting
    private fun toScrapedEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val id = node.stringOrNull("_id") ?: return null
        val title = node.stringOrNull("title") ?: return skip(id, "no title")
        if (node.path("dateTBA").asBoolean(false)) return skip(id, "date still TBA")
        val date = node.stringOrNull("date")?.let { parseIsoDate(it) } ?: return skip(id, "no usable date")

        return ScrapedEvent(
            title = title,
            description = node.stringOrNull("description")?.takeUnless { it.equals("TBA", ignoreCase = true) },
            eventType = EventType.PARTY.name,
            // The venue names no style but programmes techno nights, so the venue is the default.
            genre = "Techno",
            eventDate = date,
            startTime = parseClockPrefix(node.stringOrNull("time")),
            imageUrl = flyerUrl(node),
            sourceUrl = "$baseUrl#event-$id",
            sourceId = "${EventSource.ROSA.sourceIdPrefix}$id",
            ticketUrl = node.stringOrNull("raTicketUrl")
        )
    }

    private fun skip(
        id: String,
        reason: String
    ): ScrapedEvent? {
        logger.warn { "Skipping ROSA event $id: $reason" }
        return null
    }

    /** `image-<hash>-1080x1350-png` is served as `<hash>-1080x1350.png`. */
    @Suppress("ReturnCount") // A guard per missing part reads better than nesting
    private fun flyerUrl(node: JsonNode): String? {
        val reference = node.path("flyer").path("asset").stringOrNull("_ref") ?: return null
        val parts = SANITY_ASSET_REF.matchEntire(reference) ?: return null
        val (hash, dimensions, extension) = parts.destructured
        return "$SANITY_IMAGE_BASE/$hash-$dimensions.$extension"
    }
}

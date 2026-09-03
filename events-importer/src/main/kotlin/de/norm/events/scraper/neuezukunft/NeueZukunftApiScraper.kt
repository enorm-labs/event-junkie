package de.norm.events.scraper.neuezukunft

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.elfsight.ElfsightEventNode
import de.norm.events.scraper.elfsight.elfsightActionUrl
import de.norm.events.scraper.elfsight.elfsightDescriptionText
import de.norm.events.scraper.elfsight.elfsightJsonMapper
import de.norm.events.scraper.elfsight.parseElfsightDate
import de.norm.events.scraper.elfsight.parseElfsightEventNodes
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.json.JsonMapper

/** Public landing page every event links back to — the widget exposes no per-event URLs. */
private const val NEUE_ZUKUNFT_URL = "https://neue-zukunft.org/"

/**
 * Pure parser for Neue Zukunft's concert programme, sourced from the JSON boot
 * response of the Elfsight "Event Calendar" widget embedded on its landing page
 * (`core.service.elfsight.com/p/boot/?w=<widgetId>`).
 *
 * The public page (`neue-zukunft.org`) is a static landing page: the concert
 * programme is otherwise published only as an image-based monthly PDF poster, and
 * the widget renders client-side, so neither is scrapeable as HTML. The widget's
 * boot API, however, returns every event as clean structured JSON — the most stable
 * possible source (ADR-007 §"Selector Strategy" priority 1). [NeueZukunftWebsiteImporter]
 * fetches the response body; this class parses it.
 *
 * The payload shape and its readers are shared with the other Elfsight venue — see
 * [de.norm.events.scraper.elfsight.ElfsightEventNode]. Each event carries an `id`, `name`, a
 * `start.{date,time}`, an HTML `description`, a `coverImage.url`, and `actions[]` (a "Get Tickets"
 * link, or a "Sold Out!" marker with an empty link). Neue Zukunft is a live-music venue with no
 * event-category field, so the type defaults to `CONCERT`, flipping to `FESTIVAL` only for an
 * unambiguous festival title ([isFestivalTitle]).
 *
 * The widget returns the venue's **whole calendar**, including shows that have already
 * happened; those past-dated events are dropped centrally at persistence time
 * (`EventUpsertService`), so this parser returns every calendar entry as-is.
 *
 * @see NeueZukunftWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://neue-zukunft.org/">Neue Zukunft</a>
 */
class NeueZukunftApiScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = elfsightJsonMapper()

    /**
     * Parses every event from the Elfsight widget boot response [json].
     *
     * @param json the raw JSON body of the `p/boot/?w=<widgetId>` response.
     * @return a list of [ScrapedEvent] instances, one per calendar entry; empty if the
     *   payload is absent, unparseable, or carries no events.
     */
    fun scrape(json: String): List<ScrapedEvent> {
        val eventNodes = parseElfsightEventNodes(jsonMapper, json, VENUE_NAME) ?: return emptyList()
        logger.info { "Found ${eventNodes.size} event(s) in Neue Zukunft widget response" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        val parsed =
            eventNodes.mapNotNull { node ->
                try {
                    parseEvent(jsonMapper.treeToValue(node, ElfsightEventNode::class.java))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Neue Zukunft event, skipping" }
                    null
                }
            }

        return parsed
    }

    @Suppress("ReturnCount") // Guard clauses for the required id, title, and date are clearer than nesting.
    private fun parseEvent(node: ElfsightEventNode): ScrapedEvent? {
        val id = node.id.blankToNull()
        if (id == null) {
            logger.warn { "Neue Zukunft event has no id, skipping" }
            return null
        }

        val title = node.name.blankToNull()
        if (title == null) {
            logger.warn { "Neue Zukunft event '$id' has no name, skipping" }
            return null
        }

        val eventDate = parseElfsightDate(node.start?.date)
        if (eventDate == null) {
            logger.warn { "Neue Zukunft event '$id' has no parseable date, skipping" }
            return null
        }

        // All-day entries carry a placeholder time; only a real clock value becomes a start time.
        val startTime = if (node.isAllDay) null else parseTime(node.start?.time.blankToNull())

        val festival = isFestivalTitle(title)
        val eventType = if (festival) EventType.FESTIVAL.name else EventType.CONCERT.name

        return ScrapedEvent(
            title = title,
            description = elfsightDescriptionText(node.description),
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl =
                node.coverImage
                    ?.url
                    .blankToNull()
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = NEUE_ZUKUNFT_URL,
            sourceId = "${EventSource.NEUE_ZUKUNFT.sourceIdPrefix}$id",
            ticketUrl = elfsightActionUrl(node.actions),
            soldOut = node.actions.any { it.text.blankToNull()?.contains("sold out", ignoreCase = true) == true },
            // A festival title names an event, not a performer; only concerts mint headliners from the title.
            artists = if (festival) emptyList() else headlinersFromTitle(title)
        )
    }

    private companion object {
        /** Names the venue in the shared payload reader's warnings. */
        const val VENUE_NAME = "Neue Zukunft"
    }
}

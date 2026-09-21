package de.norm.events.scraper.modus

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Modus Berlin's `/events` programme page.
 *
 * The whole programme, unpaginated, as `.event-item` tiles. Each is an `a.event-link` to the
 * `/event/DDMMYY-<Name>` detail page wrapping a poster and a `figcaption` holding a German
 * `DD.MM.YYYY` date in a bare `div` and the title in an `h2`.
 *
 * The **rendered** date is read, never the slug's: the slug is minted once and keeps the
 * original date when a show moves (`160426-LunaSimao` renders as `13.04.2027`). The slug serves
 * only the stable [ScrapedEvent.sourceId], so a postponed show keeps its identity.
 *
 * @see ModusDetailPageScraper for the detail-page data (start time, ticket link, description).
 * @see ModusWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://modus-berlin.de/events">Modus event listing</a>
 */
class ModusOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event tiles from the programme page, one [ScrapedEvent] per tile.
     *
     * @param baseUrl the URL the document was fetched from, for detail links and `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val tiles = document.select(".event-tiles .event-item")
        logger.info { "Found ${tiles.size} event tile(s) on Modus overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed tiles without aborting the import
        return tiles.mapNotNull { tile ->
            try {
                parseTile(tile, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Modus event tile, skipping" }
                null
            }
        }
    }

    /** Parses one `.event-item` tile into a [ScrapedEvent], or `null` without a link or title. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title are clearer than nesting
    private fun parseTile(
        tile: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = tile.attrAt("a.event-link[href]", "href") ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/event/")

        val rawTitle = tile.textAt("figcaption h2") ?: return null
        val title = cleanEventTitle(rawTitle)
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            // The bare div beside the h2 carries the rendered German date — see the class KDoc for why
            // the slug's DDMMYY is ignored.
            eventDate = parseGermanDate(tile.textAt("figcaption div")) ?: UNRESOLVED_EVENT_DATE,
            imageUrl = tile.imgSrcAt("img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MODUS.sourceIdPrefix}$slug",
            // The venue marks a move only in the title prose ("(verschoben aus 2026)"), which
            // cleanEventTitle strips — so the status is read from the raw title.
            status = parseEventStatus(rawTitle),
            statusNote = rawTitle,
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }
}

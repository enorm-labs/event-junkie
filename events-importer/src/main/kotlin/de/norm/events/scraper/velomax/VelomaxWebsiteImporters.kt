package de.norm.events.scraper.velomax

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Shared importer for the three halls Velomax programmes on one listing.
 *
 * All three read the same `velomax.de/events` page and keep only their own [hall]'s entries, then
 * follow each entry to the hall's own `/events/event/<slug>` detail page — which carries the event
 * as schema.org Microdata. The listing supplies discovery, the hall filter and the venue's own
 * event type; the detail page supplies everything else.
 *
 * @see VelomaxOverviewPageScraper for the listing (discovery, hall filter, sport exclusion).
 * @see VelomaxDetailPageScraper for the Microdata detail pages.
 */
abstract class AbstractVelomaxHallImporter(
    htmlFetcher: HtmlFetcher,
    private val hall: VelomaxHall
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource get() = hall.eventSource

    private val overviewPageScraper = VelomaxOverviewPageScraper()
    private val detailPageScraper = VelomaxDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url, hall)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url, hall)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]).
     *
     * The detail page's Microdata is authoritative for the date and status, and is the only source
     * of the doors time, description, poster, ticket link and promoter. Four fields keep the
     * **listing's** value:
     * - **`startTime`**, because a run playing several sessions in one day links all of them to one
     *   detail page, which states a single start time for the lot;
     * - **`sourceId`**, for that same reason and it is the load-bearing one: the detail page's id is
     *   derived from a permalink that is one page per *show*, so every session of a day would come
     *   back under the same id and `event.source_id`'s UNIQUE constraint would keep only one. The
     *   listing is the sole per-session source, so its session-keyed id
     *   ([VelomaxOverviewPageScraper]) is the identity that must survive the merge;
     * - the venue's own **`eventType`**, which the detail page does not restate and which is what
     *   separates a concert from a staged show here;
     * - the **sold-out** flag, which the listing carries as a ticket signal and the Microdata does
     *   not express.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            subtitle = primary.subtitle ?: fallback.subtitle,
            eventType = fallback.eventType ?: primary.eventType,
            startTime = fallback.startTime ?: primary.startTime,
            sourceId = fallback.sourceId,
            soldOut = primary.soldOut || fallback.soldOut,
            status = primary.status.takeIf { it != EventStatus.SCHEDULED.name } ?: fallback.status,
            artists = primary.artists.ifEmpty { fallback.artists }
        )
}

/**
 * Website importer for the Max-Schmeling-Halle — the `.msh` entries on the shared Velomax listing.
 * Most of this hall's programme is sport, which is deliberately not imported, so its concert and
 * show count is well below the number of entries the listing shows for it.
 */
@Component
class MaxSchmelingHalleWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVelomaxHallImporter(htmlFetcher, VelomaxHall.MAX_SCHMELING_HALLE)

/** Website importer for the Velodrom — the `.velodrom` entries on the shared Velomax listing. */
@Component
class VelodromWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVelomaxHallImporter(htmlFetcher, VelomaxHall.VELODROM)

/**
 * Website importer for UFO im Velodrom — the `.ufo` entries on the shared Velomax listing, served
 * from the hall's own `ufo-velodrom.de` domain.
 */
@Component
class UfoImVelodromWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVelomaxHallImporter(htmlFetcher, VelomaxHall.UFO_IM_VELODROM)

/** Nothing this source withholds needs declaring (#715). */
val VELOMAX_LIMITATIONS =
    VenueLimitations(
        sources =
            setOf(
                EventSource.MAX_SCHMELING_HALLE,
                EventSource.UFO_IM_VELODROM,
                EventSource.VELODROM
            )
    )

package de.norm.events.scraper.velomax

import de.norm.events.event.EventStatus
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Shared importer for the three halls Velomax programmes on one listing.
 *
 * All three read `velomax.de/events`, keep only their own [hall]'s entries, then follow each to
 * the hall's `/events/event/<slug>` detail page — schema.org Microdata. The listing supplies
 * discovery, the hall filter and the venue's event type; the detail page everything else.
 *
 * @see VelomaxOverviewPageScraper for the listing (discovery, hall filter, sport exclusion).
 * @see VelomaxDetailPageScraper for the Microdata detail pages.
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the venue importers below it; an instance of it alone names no venue.
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
     * The Microdata is authoritative for date and status and the only source of doors time,
     * description, poster, ticket link and promoter. Four fields keep the **listing's** value:
     * - **`startTime`**: a run playing several sessions a day links all to one detail page, which
     * states one start time for the lot;
     * - **`sourceId`**, the load-bearing one for the same reason: the detail page's id comes from
     * a permalink that is one page per *show*, so every session of a day would share an id and
     * `event.source_id`'s UNIQUE constraint would keep one. The listing is the sole per-session
     * source, so its session-keyed id ([VelomaxOverviewPageScraper]) must survive the merge;
     * - the venue's **`eventType`**, which the detail page does not restate and which separates a
     * concert from a staged show here;
     * - the **sold-out** flag, a listing ticket signal the Microdata does not express.
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
 * The Max-Schmeling-Halle — the `.msh` entries. Most of its programme is sport, deliberately
 * not imported, so its concert and show count is well below the listing's entries for it.
 */
@Component
class MaxSchmelingHalleWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVelomaxHallImporter(htmlFetcher, VelomaxHall.MAX_SCHMELING_HALLE)

/** The Velodrom — the `.velodrom` entries on the shared Velomax listing. */
@Component
class VelodromWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVelomaxHallImporter(htmlFetcher, VelomaxHall.VELODROM)

/**
 * UFO im Velodrom — the `.ufo` entries, served from the hall's own `ufo-velodrom.de` domain.
 */
@Component
class UfoImVelodromWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVelomaxHallImporter(htmlFetcher, VelomaxHall.UFO_IM_VELODROM)

val VELOMAX_LIMITATIONS =
    VenueLimitations(
        sources =
            setOf(
                EventSource.MAX_SCHMELING_HALLE,
                EventSource.UFO_IM_VELODROM,
                EventSource.VELODROM
            ),
        limitations =
            listOf(
                AcceptedLimitation(LimitedAspect.PRICE, "the listing and the event pages print no figure; tickets are sold through outside shops")
            )
    )

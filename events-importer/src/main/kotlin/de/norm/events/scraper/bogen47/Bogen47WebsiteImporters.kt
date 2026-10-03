package de.norm.events.scraper.bogen47

import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.walkWpRestPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Shared importer for the BOGEN47 venues whose sites run one WordPress theme: LARK and Fitzroy.
 *
 * An Advanced Custom Fields `event` post type exposed in full over `/wp-json/wp/v2/event`, so
 * nothing is scraped (ADR-007 §"Selector Strategy" priority 1): walk the listing newest-first
 * via [ApiClient.fetchJson], parse each page with [Bogen47ApiScraper], then resolve the upcoming
 * events' posters in one batched `/wp-json/wp/v2/media?include=<ids>` request.
 *
 * **Paging usually stops after one request** because the theme overloads `post.date` with the
 * *event* date, making newest-first chronological in event terms — the upcoming programme sits
 * at the front of page 1 and paging stops once a page's oldest event is past.
 * [HeimathafenWebsiteImporter][de.norm.events.scraper.heimathafen.HeimathafenWebsiteImporter]
 * walks its whole archive for want of that. [MAX_PAGES] still bounds the loop.
 *
 * **The poster is fetched separately**: the listing carries only a `featured_media` id, and
 * `_embed` inlines every generated size for every post — 308 KB → 845 KB in a live capture,
 * against ~2 KB for one batched lookup. A failed media response costs posters, never events.
 *
 * No conditional request: `etag` / `lastModified` are ignored and every import returns
 * [ImportResult.Success], safe because persistence upserts idempotently by `sourceId`.
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the venue importers below it; an instance of it alone names no venue.
abstract class AbstractBogen47WebsiteImporter(
    private val apiClient: ApiClient,
    final override val eventSource: EventSource,
    eventsUrl: String,
    eventTypes: Map<String, String>,
    /** Clock deciding which events are still upcoming; override in tests. */
    private val clock: Clock
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    private val apiScraper = Bogen47ApiScraper(eventSource, eventsUrl, eventTypes, clock)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val today = LocalDate.now(clock)
        // Ordered by event date, so a page reaching the past holds no more upcoming events.
        val listing =
            apiClient.walkWpRestPages(eventSource, { buildListingUrl(url, it) }, PER_PAGE, MAX_PAGES, apiScraper::scrapePage) { page ->
                page.oldestDate?.let { it < today } == true
            }

        val events = withPosters(listing.items.distinctBy { it.event.sourceId }, url)
        logger.info { "Scraped ${events.size} upcoming event(s) for $eventSource" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = listing.complete)
    }

    /** Resolves the entries' `featured_media` ids in one request and applies the URLs to the events. */
    private suspend fun withPosters(
        entries: List<Bogen47Entry>,
        baseUrl: String
    ): List<ScrapedEvent> {
        val mediaIds = entries.mapNotNull { it.featuredMediaId }.distinct()
        if (mediaIds.isEmpty()) return entries.map { it.event }

        val posters = apiScraper.parseMedia(apiClient.fetchJson(buildMediaUrl(baseUrl, mediaIds)))
        if (posters.isEmpty()) logger.warn { "$eventSource media lookup returned no posters for ${mediaIds.size} attachment(s)" }

        return entries.map { entry ->
            entry.event.copy(imageUrl = entry.featuredMediaId?.let { posters[it] })
        }
    }

    /**
     * The WP REST query for one listing [page] from the configured API base [baseUrl]. Page size
     * and field projection are parsing concerns and live in code (ADR-007: parsing logic in code,
     * entry-point URL in config). The base is on the event source, e.g.
     * `https://larkberlin.com/wp-json/wp/v2/event`.
     */
    private fun buildListingUrl(
        baseUrl: String,
        page: Int
    ): String = "$baseUrl${baseUrl.querySeparator()}per_page=$PER_PAGE&page=$page&_fields=$FIELDS"

    /** The batched attachment lookup, sharing the listing's host and `/wp/v2` namespace. */
    private fun buildMediaUrl(
        baseUrl: String,
        mediaIds: List<Long>
    ): String {
        val root = baseUrl.substringBefore('?').trimEnd('/').substringBeforeLast('/')
        return "$root/media?include=${mediaIds.joinToString(",")}&per_page=$PER_PAGE&_fields=id,source_url"
    }

    private companion object {
        /** WordPress's maximum page size, so the programme needs the fewest requests. */
        const val PER_PAGE = 100

        /**
         * Safety bound on the paging loop. One page covers the whole upcoming programme today; the
         * cap makes a runaway loop impossible if the ordering assumption ever breaks, logged when hit.
         */
        const val MAX_PAGES = 10

        /**
         * The fields the parser reads. `date` matters most: the theme stores the *event* date there, so
         * it supplies both the date and the doors time.
         */
        const val FIELDS = "id,link,title,date,acf,featured_media"
    }
}

/** LARK — a live-music club in the railway arches on Holzmarktstraße. */
@Component
class LarkWebsiteImporter(
    apiClient: ApiClient,
    clock: Clock = Clock.systemDefaultZone()
) : AbstractBogen47WebsiteImporter(apiClient, EventSource.LARK, "https://larkberlin.com/events/", LARK_EVENT_TYPES, clock)

/** Fitzroy — LARK's sister club next door; the site types every event `Party`, live shows included. */
@Component
class FitzroyWebsiteImporter(
    apiClient: ApiClient,
    clock: Clock = Clock.systemDefaultZone()
) : AbstractBogen47WebsiteImporter(apiClient, EventSource.FITZROY, "https://fitzroy-berlin.de/events/", emptyMap(), clock)

/**
 * LARK's `acf.event_type` vocabulary beyond the shared synonyms. `Live` is the venue's word for a
 * gig; `Club` and `Dance` are both DJ nights; `Seminar` is a workshop with no closer type than
 * `OTHER`.
 */
internal val LARK_EVENT_TYPES: Map<String, String> =
    mapOf(
        "live" to EventType.CONCERT.name,
        "club" to EventType.PARTY.name,
        "dance" to EventType.PARTY.name,
        "seminar" to EventType.OTHER.name
    )

val LARK_LIMITATIONS =
    VenueLimitations(
        EventSource.LARK,
        AcceptedLimitation(LimitedAspect.START_TIME, "the venue renders its one time as Doors and publishes no separate start time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure; tickets link to hum-berlin.com"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue leaves its genre fields empty on every event; style appears only in the description")
    )

val FITZROY_LIMITATIONS =
    VenueLimitations(
        EventSource.FITZROY,
        AcceptedLimitation(LimitedAspect.START_TIME, "the venue renders its one time as Doors and publishes no separate start time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure; tickets link to Resident Advisor"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue leaves its genre fields empty on every event; style appears only in the description"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the venue types every event Party, a live show included"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the organiser field is empty on every event; a collective is named only in the title")
    )

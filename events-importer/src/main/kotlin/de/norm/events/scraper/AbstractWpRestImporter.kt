package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * One import's walk over a WordPress collection: the page URL, the page reader and the early stop
 * rule of [walkWpRestPages]. [AbstractWpRestImporter.walk] builds it once per run, so a date cut-off
 * holds for every page.
 */
class WpRestWalk<T, P : WpRestPage<T>>(
    val pageUrl: (Int) -> String,
    val read: (String) -> P,
    val lastPage: (P) -> Boolean = { false }
)

/**
 * Base class for a venue whose programme is a paged WordPress REST collection (`/wp-json/wp/v2/…`,
 * the WooCommerce Store API). It walks the pages with [walkWpRestPages], turns the items into events
 * with [toEvents] and logs [scrapedMessage]. [ApiClient] makes no conditional request, so every run is
 * an [ImportResult.Success] without validators, and idempotent `sourceId` upserts do the change
 * detection. The paged counterpart of [AbstractJsonApiImporter].
 */
abstract class AbstractWpRestImporter<T, P : WpRestPage<T>>(
    private val apiClient: ApiClient,
    private val perPage: Int,
    private val maxPages: Int
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)

    /** The walk for the configured [url]. */
    protected abstract fun walk(url: String): WpRestWalk<T, P>

    /** The events of the walked [listing]: a dedupe, or a fold and an enrichment pass. */
    protected abstract suspend fun toEvents(
        listing: WalkedListing<T>,
        url: String
    ): List<ScrapedEvent>

    /** The INFO line for one run. Each venue keeps its own wording, because production log searches match it. */
    protected abstract fun scrapedMessage(
        events: List<ScrapedEvent>,
        listing: WalkedListing<T>
    ): String

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val walk = walk(url)
        val listing = apiClient.walkWpRestPages(eventSource, walk.pageUrl, perPage, maxPages, walk.read, walk.lastPage)
        val events = toEvents(listing, url)
        logger.info { scrapedMessage(events, listing) }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = listing.complete)
    }
}

package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.jsoup.nodes.Document

/**
 * Abstract base class for venue importers that follow the overview → detail page pattern:
 * 1. Fetch the overview page and discover events.
 * 2. For each discovered event, fetch its detail page for richer data.
 * 3. Merge detail and overview data, preferring the detail page.
 *
 * Subclasses provide venue-specific scrapers and a gap-filling strategy;
 * this class owns the shared fetch orchestration.
 *
 * **This is the only class in the package that performs I/O.** Every `*PageScraper` / `*ApiScraper`
 * takes a pre-fetched [Document] or response body, which is what makes them testable against a
 * saved fixture — a property of the pattern, stated here rather than repeated in every venue's
 * KDoc.
 */
abstract class AbstractTwoPageWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    // Use javaClass.name so logs identify the concrete subclass
    // (Cassiopeia / MadameClaude) rather than this abstract base.
    private val logger = KotlinLogging.logger(javaClass.name)

    /** Parses all events from the overview page HTML. */
    protected abstract fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent>

    /** Parses the detail page for a single event, or null if the page cannot be parsed. */
    protected abstract fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent?

    /**
     * Fills missing fields in [primary] (detail page data) from [fallback] (overview data).
     *
     * Only called when [scrapeDetail] succeeds. Implementations should fill only fields
     * that the detail page cannot supply (e.g. image URL).
     */
    protected abstract fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val overviewEvents = scrapeOverview(fetchResult.document, url)
                logger.info { "Scraped ${overviewEvents.size} event(s) from ${eventSource.name} overview" }
                val events = overviewEvents.map { parseDetailOrFallback(it) }.let(::dropUnresolvedDates)
                ImportResult.Success(events, fetchResult.etag, fetchResult.lastModified)
            }
        }

    /**
     * Drops events whose date is still the [UNRESOLVED_EVENT_DATE] sentinel after the merge —
     * i.e. neither the overview nor the detail page supplied a real date (e.g. a dateless
     * featured teaser whose detail page was unavailable). Persisting these would produce a
     * garbage slug and date, so they are discarded with a warning rather than stored.
     */
    private fun dropUnresolvedDates(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val (resolved, unresolved) = events.partition { it.eventDate != UNRESOLVED_EVENT_DATE }
        unresolved.forEach { event ->
            logger.at(Level.WARN) {
                message = "Dropping '${event.title}': no event date resolved from overview or detail page"
                payload = mapOf(LogContext.Fields.URL to event.sourceUrl, LogContext.Fields.EVENT_SOURCE_ID to event.sourceId)
            }
        }
        return resolved
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: degrade to overview data if detail page is unavailable
    private suspend fun parseDetailOrFallback(overview: ScrapedEvent): ScrapedEvent =
        try {
            val detailDoc = htmlFetcher.fetchDocument(overview.sourceUrl)
            val detail = scrapeDetail(detailDoc, overview.sourceUrl)
            if (detail != null) fillGapsFromOverview(primary = detail, fallback = overview) else overview
        } catch (e: Exception) {
            logger.at(Level.WARN) {
                message = "Failed to fetch detail page for '${overview.title}', using overview data"
                cause = e
                payload = mapOf(LogContext.Fields.URL to overview.sourceUrl, LogContext.Fields.EVENT_SOURCE_ID to overview.sourceId)
            }
            overview
        }
}

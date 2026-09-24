package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

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

    /**
     * The absolute URL of the listing page after [document], or null when it is the last. Null by
     * default: most listings are one page. A subclass that returns one has its later pages fetched
     * and scraped too, up to [MAX_OVERVIEW_PAGES] (ADR-007 §"Pagination — First Page Only").
     */
    protected open fun nextOverviewPage(
        document: Document,
        url: String
    ): String? = null

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
                val overviewEvents = scrapeOverviewPages(fetchResult.document, url)
                logger.info { "Scraped ${overviewEvents.size} event(s) from ${eventSource.name} overview" }
                val merged = overviewEvents.map { parseDetailOrFallback(it) }
                val events = dropUnresolvedDates(merged)
                ImportResult.Success(
                    events,
                    fetchResult.etag,
                    fetchResult.lastModified,
                    droppedUnresolvedDate = merged.size - events.size
                )
            }
        }

    /**
     * Scrapes [first] and every later page [nextOverviewPage] names. A later page is fetched without
     * validators: they cover the entry page only. One that fails is logged and ends the walk, keeping
     * what was read; stale-event cleanup is scoped to the scraped dates, so the unread tail survives.
     * An event that moved across a page boundary between two requests is kept once.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: keep the pages already read if a later one fails
    private suspend fun scrapeOverviewPages(
        first: Document,
        url: String
    ): List<ScrapedEvent> {
        val events = scrapeOverview(first, url).toMutableList()
        var next = nextOverviewPage(first, url)
        var pages = 1
        while (next != null && pages < MAX_OVERVIEW_PAGES) {
            val pageUrl: String = next
            val document =
                try {
                    htmlFetcher.fetchDocument(pageUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "${eventSource.name} overview page ${pages + 1} failed ($pageUrl); importing the $pages page(s) read" }
                    break
                }
            events += scrapeOverview(document, pageUrl)
            next = nextOverviewPage(document, pageUrl)
            pages++
        }
        if (next != null && pages == MAX_OVERVIEW_PAGES) {
            logger.warn { "${eventSource.name} pagination hit the $MAX_OVERVIEW_PAGES-page cap before the listing ended; later pages were not read" }
        }
        return events.distinctBy { it.sourceId }
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
                payload = mapOf(LogFields.URL to event.sourceUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
            }
        }
        return resolved
    }

    /**
     * Degrades to the overview row when the detail page is unavailable — except for a row that has
     * no date without it (a Kulturhäuser featured teaser, #312), which is dropped rather than
     * degraded, so that one gets a second fetch after [RETRY_PAUSE] before the run gives up on it.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: degrade to overview data if detail page is unavailable
    private suspend fun parseDetailOrFallback(overview: ScrapedEvent): ScrapedEvent {
        val attempts = if (overview.eventDate == UNRESOLVED_EVENT_DATE) DATELESS_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            try {
                return mergeDetail(overview)
            } catch (e: Exception) {
                val last = attempt == attempts - 1
                logger.at(Level.WARN) {
                    message =
                        if (last) {
                            "Failed to fetch detail page for '${overview.title}', using overview data"
                        } else {
                            "Failed to fetch detail page for '${overview.title}', which has no date without it; retrying once"
                        }
                    cause = e
                    payload = mapOf(LogFields.URL to overview.sourceUrl, LogFields.EVENT_SOURCE_ID to overview.sourceId)
                }
                if (!last) delay(RETRY_PAUSE)
            }
        }
        return overview
    }

    private suspend fun mergeDetail(overview: ScrapedEvent): ScrapedEvent {
        val detailDoc = htmlFetcher.fetchDocument(overview.sourceUrl)
        // The scope opens AFTER the fetch, deliberately: the fetch already writes `url` as a
        // payload field, and a line inside both would carry the key twice (#982). Everything
        // inside is the scraper's own parsing, which is what needed the URL and never had it.
        return withContext(LogContext.forPage(overview.sourceUrl)) {
            val detail = scrapeDetail(detailDoc, overview.sourceUrl)
            if (detail != null) fillGapsFromOverview(primary = detail, fallback = overview) else overview
        }
    }

    private companion object {
        /** Fetches for a row whose date lives only on its detail page. */
        const val DATELESS_ATTEMPTS = 2

        /** A runaway guard on [nextOverviewPage]; Cassiopeia's listing runs to seven pages. */
        const val MAX_OVERVIEW_PAGES = 12

        /** Long enough for a momentary refusal to pass; short enough not to stall the run. */
        val RETRY_PAUSE = 5.seconds
    }
}

package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
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
 * Subclasses pass their scrapers to the constructor, as `VenueOverviewPageScraper()::scrape`, and may
 * override the gap-filling strategy; this class owns the shared fetch orchestration.
 *
 * **This is the only class in the package that performs I/O.** Every `*PageScraper` / `*ApiScraper`
 * takes a pre-fetched [Document] or response body, which is what makes them testable against a
 * saved fixture — a property of the pattern, stated here rather than repeated in every venue's
 * KDoc.
 */
abstract class AbstractTwoPageWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Parses all events from the overview page HTML. */
    private val scrapeOverview: (Document, String) -> List<ScrapedEvent>,
    /** Parses the detail page for a single event, or null if the page cannot be parsed. */
    private val scrapeDetail: (Document, String) -> ScrapedEvent?
) : EventImporter {
    // Use javaClass.name so logs identify the concrete subclass
    // (Cassiopeia / MadameClaude) rather than this abstract base.
    private val logger = KotlinLogging.logger(javaClass.name)

    /** Every run fetches the detail pages, which the overview's validators do not cover. */
    final override val fetchesBeyondEntryPage: Boolean get() = true

    /**
     * Merges [primary] (detail page data) with [fallback] (overview data), only when the detail
     * scraper succeeds. By default the detail page wins and every gap is filled from the overview
     * ([ScrapedEvent.withGapsFrom]), so a field either page adds is kept without naming it here
     * (#1408). A venue overrides this only for a field where the overview is authoritative, as
     * `primary.withGapsFrom(fallback).copy(…)`.
     */
    protected open fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent = primary.withGapsFrom(fallback)

    /**
     * Whether a good run stores the detail page's image over the listing's. True by default, so a
     * degraded row's listing image yields to the stored one ([ScrapedEvent.listingImageStandsIn],
     * #2465). False where [fillGapsFromOverview] keeps the listing's image, or the detail page has none.
     */
    protected open val detailPageOwnsImage: Boolean = true

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
                val overview =
                    htmlFetcher.scrapeListingPages(
                        eventSource,
                        fetchResult.document,
                        url,
                        MAX_OVERVIEW_PAGES,
                        ::nextOverviewPage,
                        scrapeOverview
                    )
                logger.info { "Scraped ${overview.events.size} event(s) from ${eventSource.name} overview" }
                val merged = overview.events.map { parseDetailOrFallback(it) }
                val events = dropUnresolvedDates(merged)
                ImportResult.Success(
                    events,
                    fetchResult.etag,
                    fetchResult.lastModified,
                    droppedUnresolvedDate = merged.size - events.size,
                    complete = overview.complete
                )
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
                payload = mapOf(LogFields.URL to event.sourceUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
            }
        }
        return resolved
    }

    /**
     * Degrades to the overview row when the detail page is unavailable — except for a row that has
     * no date without it (a Kulturhäuser featured teaser, #312), which is dropped rather than
     * degraded, so that one gets a second fetch after [RETRY_PAUSE] before the run gives up on it.
     * A degraded row is marked [ScrapedEvent.detailUnavailable], so the upsert keeps the fields the
     * detail page stored last time.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: degrade to overview data if detail page is unavailable
    private suspend fun parseDetailOrFallback(overview: ScrapedEvent): ScrapedEvent {
        val attempts = if (overview.eventDate == UNRESOLVED_EVENT_DATE) DATELESS_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            try {
                return mergeDetail(overview)
            } catch (e: CancellationException) {
                throw e
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
        return degraded(overview)
    }

    private fun degraded(overview: ScrapedEvent): ScrapedEvent = overview.copy(detailUnavailable = true, listingImageStandsIn = detailPageOwnsImage)

    private suspend fun mergeDetail(overview: ScrapedEvent): ScrapedEvent {
        val detailDoc = htmlFetcher.fetchDocument(overview.sourceUrl)
        // The scope opens AFTER the fetch, deliberately: the fetch already writes `url` as a
        // payload field, and a line inside both would carry the key twice (#982). Everything
        // inside is the scraper's own parsing, which is what needed the URL and never had it.
        return withContext(LogContext.forPage(overview.sourceUrl)) {
            val detail = scrapeDetail(detailDoc, overview.sourceUrl)
            if (detail != null) fillGapsFromOverview(primary = detail, fallback = overview) else degraded(overview)
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

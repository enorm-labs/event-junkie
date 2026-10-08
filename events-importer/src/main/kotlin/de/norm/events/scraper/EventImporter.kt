package de.norm.events.scraper

/**
 * Contract for venue-specific event importers.
 *
 * Each implementation owns the full fetch → parse pipeline for a specific
 * venue's website. Implementations are registered as Spring beans and
 * dispatched by their [eventSource] property, which must match an
 * [EventSource] enum value.
 *
 * Importers own all HTTP fetching, both overview and detail pages, keeping I/O in one place.
 * HTML parsing is delegated to `*OverviewPageScraper` and `*DetailPageScraper` classes that
 * operate on a parsed Jsoup Document and perform no I/O themselves — which is what makes a
 * scraper testable against a saved snapshot.
 */
interface EventImporter {
    /** The event source this importer handles; `EventImportService` dispatches on it. */
    val eventSource: EventSource

    /**
     * Whether one [importEvents] run returns the venue's whole standing programme: no page cap, no
     * month walk, no horizon. True opens the stale cleanup past the scrape's last date (#1974). A
     * wrong true deletes real events beyond what was fetched on every run, so it is opt-in.
     */
    val listsWholeProgramme: Boolean get() = false

    /**
     * Whether a run fetches anything past the entry page: detail pages, later listing pages, month
     * pages. True makes `EventImportService` fetch unconditionally, because the entry page's ETag or
     * Last-Modified says nothing about those pages, and a 304 on it would skip them all (#2020).
     */
    val fetchesBeyondEntryPage: Boolean get() = false

    /**
     * Imports events from the venue's website: fetches the overview page, parses its listings, and
     * optionally fetches detail pages for enrichment. [etag] and [lastModified] make the fetch
     * conditional.
     *
     * @return an [ImportResult] with the scraped events and fresh cache headers, or
     *   [ImportResult.NotModified] when the page has not changed.
     */
    suspend fun importEvents(
        url: String,
        etag: String? = null,
        lastModified: String? = null
    ): ImportResult
}

/**
 * Result of an [EventImporter.importEvents] call.
 */
sealed interface ImportResult {
    /** The page has not been modified since the last fetch (304 response). */
    data object NotModified : ImportResult

    data class Success(
        val events: List<ScrapedEvent>,
        /** New ETag header from the response, if present. */
        val etag: String?,
        /** New Last-Modified header from the response, if present. */
        val lastModified: String?,
        /** Discarded for want of a date (#982). Zero means "not measured", not "none dropped". */
        val droppedUnresolvedDate: Int = 0,
        /** False when a page that holds events failed to load, which skips the stale cleanup (#1980). */
        val complete: Boolean = true
    ) : ImportResult
}

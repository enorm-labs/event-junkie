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
    /**
     * The event source this importer handles.
     *
     * Used for dispatch: the [EventImportService] maps each [EventSourceEntity]
     * to its importer via this property.
     */
    val eventSource: EventSource

    /**
     * Imports events from the venue's website.
     *
     * The importer is responsible for:
     * 1. Fetching the overview page HTML via [HtmlFetcher].
     * 2. Parsing event listings from the overview page.
     * 3. Optionally fetching and parsing detail pages for enrichment.
     * 4. Returning a list of [ScrapedEvent] instances ready for upserting.
     *
     * @param url the event listing page URL to scrape.
     * @param etag optional cached ETag for conditional requests.
     * @param lastModified optional cached Last-Modified for conditional requests.
     * @return an [ImportResult] with the scraped events and updated cache headers,
     *   or [ImportResult.NotModified] if the page hasn't changed.
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
        val lastModified: String?
    ) : ImportResult
}

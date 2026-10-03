package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Base class for a venue whose whole programme is one JSON response: fetch [requestUrl] through
 * [ApiClient], parse it with [scrape], return the events. [ApiClient] makes no conditional request,
 * so every run is a [ImportResult.Success] without validators, and idempotent `sourceId` upserts do
 * the change detection. The JSON counterpart of [AbstractSinglePageWebsiteImporter].
 *
 * [scrape] takes the body and the configured URL. [venueName] is how the log line names the source.
 */
abstract class AbstractJsonApiImporter(
    private val apiClient: ApiClient,
    private val venueName: String,
    private val scrape: (String, String) -> List<ScrapedEvent>
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)

    /** The request for the configured [url]: the URL itself, or a query built on it. */
    protected open fun requestUrl(url: String): String = url

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val events = scrape(apiClient.fetchJson(requestUrl(url)), url)
        logger.info { "Scraped ${events.size} event(s) from $venueName" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}

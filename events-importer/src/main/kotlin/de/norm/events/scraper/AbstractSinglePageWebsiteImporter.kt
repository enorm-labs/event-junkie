package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Base class for a venue whose whole programme is one listing: fetch it conditionally, parse it
 * with [scrape], return the events with the page's validators. The one-page counterpart of
 * [AbstractTwoPageWebsiteImporter]. A listing paged by a next link sets [maxListingPages] and
 * [nextListingPage]; rows that share one page per production set [sharedPages]. A venue that
 * needs any other fetch implements [EventImporter] directly.
 *
 * [venueName] is how the log line names the source, kept as each venue wrote it.
 */
abstract class AbstractSinglePageWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    private val venueName: String,
    private val scrape: (Document, String) -> List<ScrapedEvent>
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)

    /**
     * Reads one event's own page into the event, or returns null when the page yields nothing.
     * Null for a venue whose listing is the whole record. When set, every event's page is fetched
     * through [withEventPageOrFlagged], which owns the failure path and the flag.
     */
    protected open val enrichFromEventPage: ((ScrapedEvent, Document) -> ScrapedEvent?)? = null

    /** The fields a good run takes from the event page over the listing's, see [withEventPageOrFlagged]. */
    protected open val eventPageOwns: Set<ScrapedField> = emptySet()

    /** Cookies sent with the listing fetch, for a venue that serves its programme only to a request carrying one. */
    protected open val cookies: Map<String, String> = emptyMap()

    /**
     * The listing in the venue's other language, for a venue that translates the event text there.
     * The run reads it too and stores its text as the second language (ADR-026 rule 2).
     */
    protected open val secondLanguage: SecondLanguageListing? = null

    /**
     * The page that several rows share, as one production's performances do, read once per page and
     * applied to each row. Null for a venue whose rows have no shared page.
     */
    protected open val sharedPages: SharedPages<*>? = null

    /** A runaway guard on [nextListingPage]; 1 reads the entry page alone. */
    protected open val maxListingPages: Int = 1

    /** The absolute URL of the listing page after [document], or null on the last. */
    protected open fun nextListingPage(
        document: Document,
        url: String
    ): String? = null

    /** A run that reads event pages or later listing pages cannot be skipped on the entry page's validators. */
    override val fetchesBeyondEntryPage: Boolean
        get() = enrichFromEventPage != null || sharedPages != null || maxListingPages > 1 || secondLanguage != null

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified, cookies)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val listing = readListing(fetchResult.document, url)
                logger.info { "Scraped ${listing.events.size} event(s) from $venueName" }

                val events =
                    secondLanguage?.let { htmlFetcher.withSecondLanguageListing(listing.events, fetchResult.document, it) } ?: listing.events
                ImportResult.Success(
                    events = withSharedPages(withEventPages(events)),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified,
                    complete = listing.complete
                )
            }
        }

    private suspend fun readListing(
        document: Document,
        url: String
    ): ListingPages =
        if (maxListingPages > 1) {
            htmlFetcher.scrapeListingPages(eventSource, document, url, maxListingPages, ::nextListingPage, scrape)
        } else {
            ListingPages(scrape(document, url), complete = true)
        }

    private suspend fun withSharedPages(events: List<ScrapedEvent>): List<ScrapedEvent> = sharedPages?.applyTo(htmlFetcher, events) ?: events

    private suspend fun withEventPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val enrich = enrichFromEventPage ?: return events
        return events.map { event -> htmlFetcher.withEventPageOrFlagged(event, eventPageOwns) { document -> enrich(event, document) } }
    }
}

/**
 * The [enrichFromSharedPages] arguments of an [AbstractSinglePageWebsiteImporter]: [parse] reads one
 * shared page, [apply] writes it into each row that links it, [pageOwns] and [secondLanguage] as there.
 */
class SharedPages<T : Any>(
    private val parse: (Document) -> T?,
    private val apply: (T, ScrapedEvent) -> ScrapedEvent,
    private val pageOwns: Set<ScrapedField> = emptySet(),
    private val secondLanguage: SecondLanguagePage? = null
) {
    internal suspend fun applyTo(
        htmlFetcher: HtmlFetcher,
        events: List<ScrapedEvent>
    ): List<ScrapedEvent> = htmlFetcher.enrichFromSharedPages(events, parse, apply, pageOwns, secondLanguage = secondLanguage)
}

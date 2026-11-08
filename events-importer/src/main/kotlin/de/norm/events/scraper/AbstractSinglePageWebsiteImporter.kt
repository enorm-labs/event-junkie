package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Base class for a venue whose whole programme is one listing: fetch it conditionally, parse it
 * with [scrape], return the events with the page's validators. The one-page counterpart of
 * [AbstractTwoPageWebsiteImporter]. A listing paged by a next link sets [maxListingPages] and
 * [nextListingPage]; rows that share one page per production set [sharedPages]; an event page that
 * can drop its row sets [eventPageReader]; a venue that reads one more page for the whole listing
 * does it in [afterListing]. A venue that needs any other fetch implements [EventImporter] directly.
 *
 * [venueName] is how the log line names the source, kept as each venue wrote it.
 */
abstract class AbstractSinglePageWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    venueName: String,
    private val scrape: (Document, String) -> List<ScrapedEvent>
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val pipeline = HtmlImportPipeline(htmlFetcher, KotlinLogging.logger(javaClass.name))

    /**
     * Reads one event's own page into the event, or returns null when the page yields nothing.
     * Null for a venue whose listing is the whole record. When set, every event's page is fetched
     * through [withEventPageOrFlagged], which owns the failure path and the flag.
     */
    protected open val enrichFromEventPage: ((ScrapedEvent, Document) -> ScrapedEvent?)? = null

    /**
     * In place of [enrichFromEventPage], for a venue whose event page can also drop its row, as a page that
     * files the event as sport does. Read with the same retry, flag and second language.
     */
    protected open val eventPageReader: EventPageReader<*>? = null

    /** Whether [event] links a page of its own rather than the listing; the event-page read takes only those. */
    protected open fun hasEventPage(event: ScrapedEvent): Boolean = true

    /** The fields a good run takes from the event page over the listing's, see [withEventPageOrFlagged]. */
    protected open val eventPageOwns: Set<ScrapedField> = emptySet()

    /**
     * The event page in the venue's other language, for a venue that translates the event text there
     * rather than on the listing. One more request per event page that changed the text (ADR-026 rule 2).
     */
    protected open val eventPageSecondLanguage: SecondLanguagePage? = null

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

    /**
     * The listing's [events] after one pass of the venue's own, given the entry [document] and its
     * [url]: a page or a request that serves every row, as a lineup or a ticket popup does. It owns
     * its failure path. [fetchesBeyondEntryPage] does not count it: a venue whose pass must not be
     * skipped on the entry page's validators overrides that too.
     */
    protected open suspend fun afterListing(
        events: List<ScrapedEvent>,
        document: Document,
        url: String
    ): List<ScrapedEvent> = events

    /** Logged with the listing's event count; null for a venue that logs its own line in [afterListing]. */
    protected open val listedLine: ((Int) -> String)? = { count -> "Scraped $count event(s) from $venueName" }

    /** Logged at the end with the events kept and the listing's count, for a venue whose event pages drop rows; null logs nothing. */
    protected open val doneLine: ((kept: Int, listed: Int) -> String)? = null

    /** A runaway guard on [nextListingPage]; 1 reads the entry page alone. */
    protected open val maxListingPages: Int = 1

    /** The absolute URL of the listing page after [document], or null on the last. */
    protected open fun nextListingPage(
        document: Document,
        url: String
    ): String? = null

    /** A run that reads event pages or later listing pages cannot be skipped on the entry page's validators. */
    override val fetchesBeyondEntryPage: Boolean
        get() = enrichFromEventPage != null || eventPageReader != null || sharedPages != null || maxListingPages > 1 || secondLanguage != null

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        pipeline.run(
            HtmlImport(
                entry = HtmlImport.Entry.Conditional(cookies),
                readListing = ::readListing,
                listedLine = listedLine,
                afterListing = ::afterListing,
                secondLanguageListing = secondLanguage,
                eventPages = eventPageRead()?.let { HtmlImport.EventPages.Enrich(it, ::hasEventPage, eventPageOwns, eventPageSecondLanguage) },
                sharedPages = sharedPages,
                doneLine = doneLine?.let { line -> { events, listing -> line(events.size, listing.events.size) } }
            ),
            url,
            etag,
            lastModified
        )

    private fun eventPageRead(): EventPageReader<*>? = eventPageReader ?: enrichFromEventPage?.let { EventPageReader.enriching(it) }

    private suspend fun readListing(
        document: Document,
        url: String
    ): HtmlImport.Listing =
        if (maxListingPages > 1) {
            val walked = scrapeListingPages(eventSource, document, url, maxListingPages, htmlFetcher::fetchDocument, ::nextListingPage, scrape)
            HtmlImport.Listing(walked.events, walked.complete)
        } else {
            HtmlImport.Listing(scrape(document, url), complete = true)
        }
}

/**
 * The [enrichFromSharedPages] arguments of an [AbstractSinglePageWebsiteImporter] or an
 * [AbstractMonthPagesWebsiteImporter]: [parse] reads one shared page, [apply] writes it into each
 * row that links it, [pageOwns] and [secondLanguage] as there.
 */
class SharedPages<T : Any>(
    private val parse: (Document) -> T?,
    private val apply: (T, ScrapedEvent) -> ScrapedEvent,
    private val pageOwns: Set<ScrapedField> = emptySet(),
    private val secondLanguage: SecondLanguagePage? = null
) {
    internal suspend fun applyTo(
        fetch: DocumentFetch,
        events: List<ScrapedEvent>
    ): List<ScrapedEvent> = enrichFromSharedPages(fetch, events, parse, apply, pageOwns, secondLanguage = secondLanguage)
}

/** [events] read through [pages], or [events] unchanged for a venue whose rows have no shared page. */
internal suspend fun withSharedPages(
    fetch: DocumentFetch,
    events: List<ScrapedEvent>,
    pages: SharedPages<*>?
): List<ScrapedEvent> = pages?.applyTo(fetch, events) ?: events

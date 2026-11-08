package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Base class for a venue whose entry page holds no events, only a link per month at
 * [monthLinkSelector] ([monthPageUrls]): fetch the entry page, read every linked month with [scrape], keep the others
 * when one fails. The entry page changes only when a month is added, so it is fetched
 * unconditionally; its validators would mask an edit inside a month. A venue whose rows link a
 * page of their own sets [enrichFromEventPage]; rows that share one page per production set [sharedPages].
 * Month links resolve against the entry page's `<base href>` where it declares one.
 */
abstract class AbstractMonthPagesWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    private val venueName: String,
    private val monthLinkSelector: String,
    private val scrape: (Document, String) -> List<ScrapedEvent>
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)
    private val pipeline = HtmlImportPipeline(htmlFetcher, logger)

    final override val fetchesBeyondEntryPage: Boolean get() = true

    /**
     * Reads one event's own page into the event, or returns null when the page yields nothing. Only
     * the events [hasEventPage] accepts are read, through [withEventPageOrFlagged].
     */
    protected open val enrichFromEventPage: ((ScrapedEvent, Document) -> ScrapedEvent?)? = null

    /** Whether [event] links a page of its own rather than the month page it was listed on. */
    protected open fun hasEventPage(event: ScrapedEvent): Boolean = true

    /** The fields a good run takes from the event page over the month page's, see [withEventPageOrFlagged]. */
    protected open val eventPageOwns: Set<ScrapedField> = emptySet()

    /** The event page in the venue's other language, as on [AbstractSinglePageWebsiteImporter.eventPageSecondLanguage]. */
    protected open val eventPageSecondLanguage: SecondLanguagePage? = null

    /** The month pages [entry] names; a venue whose months are form values rather than links builds them here. */
    protected open fun monthPageUrls(
        entry: Document,
        url: String
    ): List<String> = entry.absoluteLinksAt(monthLinkSelector, entry.baseUri().ifBlank { url })

    /** The page that several rows share, as one production's performances do, read once per page. */
    protected open val sharedPages: SharedPages<*>? = null

    /** The rows of every month as the venue's events. A venue that lists one show on many days folds them here. */
    protected open fun foldRuns(events: List<ScrapedEvent>): List<ScrapedEvent> = events

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        pipeline.run(
            HtmlImport(
                entry = HtmlImport.Entry.Unconditional,
                readListing = ::readMonths,
                eventPages =
                    enrichFromEventPage?.let {
                        HtmlImport.EventPages.Enrich(
                            EventPageReader.enriching(it),
                            ::hasEventPage,
                            eventPageOwns,
                            eventPageSecondLanguage
                        )
                    },
                sharedPages = sharedPages,
                foldRuns = ::foldRuns,
                doneLine = { events, months -> "Scraped ${events.size} $venueName event(s) across ${months.pages} month page(s)" }
            ),
            url,
            etag,
            lastModified
        )

    private suspend fun readMonths(
        entry: Document,
        url: String
    ): HtmlImport.Listing {
        val monthUrls = monthPageUrls(entry, url)
        logger.info { "Found ${monthUrls.size} month page(s) linked from $venueName entry $url" }
        val months =
            readEach(monthUrls, { "Failed to read the $venueName month page $it, importing the other months" }) { monthUrl ->
                scrape(htmlFetcher.fetchDocument(monthUrl), monthUrl)
            }
        return HtmlImport.Listing(months.items.distinctBy { it.sourceId }, months.complete, pages = monthUrls.size)
    }
}

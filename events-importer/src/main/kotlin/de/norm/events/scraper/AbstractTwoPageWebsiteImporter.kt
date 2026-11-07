package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Abstract base class for venue importers that follow the overview → detail page pattern:
 * 1. Fetch the overview page and discover events.
 * 2. For each discovered event, fetch its detail page for richer data.
 * 3. Merge detail and overview data, preferring the detail page.
 *
 * Subclasses pass their scrapers to the constructor, as `VenueOverviewPageScraper()::scrape`, and may
 * override the gap-filling strategy; this class owns the shared fetch orchestration.
 *
 * The fetch orchestration, the dateless-row retry and the undated drop live in [HtmlImportPipeline].
 * Every `*PageScraper` / `*ApiScraper` takes a pre-fetched [Document] or response body, which is
 * what makes them testable against a saved fixture.
 */
abstract class AbstractTwoPageWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Parses all events from the overview page HTML. */
    private val scrapeOverview: (Document, String) -> List<ScrapedEvent>,
    /** Parses the detail page for a single event, or null if the page cannot be parsed. */
    private val scrapeDetail: (Document, String) -> ScrapedEvent?
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val pipeline = HtmlImportPipeline(htmlFetcher, KotlinLogging.logger(javaClass.name))

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
     * The fields a good run takes from the detail page over the listing's value, so a degraded row
     * keeps the stored ones ([ScrapedEvent.detailPageOwns], #2505). The image by default (#2465).
     * Empty where [fillGapsFromOverview] keeps the listing's image, or the detail page has none.
     */
    protected open val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE)

    /**
     * The detail page in the venue's other language, for a venue that translates the event text
     * there. Each detail page's version is fetched too, one more request per event, and its text
     * is stored as the second language (ADR-026 rule 2).
     */
    protected open val secondLanguage: SecondLanguagePage? = null

    /** A runaway guard on [nextOverviewPage]; Cassiopeia's listing runs to seven pages. */
    protected open val maxOverviewPages: Int = DEFAULT_MAX_OVERVIEW_PAGES

    /** Cookies sent with the overview fetch, as on [AbstractSinglePageWebsiteImporter.cookies]. */
    protected open val cookies: Map<String, String> = emptyMap()

    /** The page that several rows share, read once per page after the detail pages, as on [AbstractSinglePageWebsiteImporter.sharedPages]. */
    protected open val sharedPages: SharedPages<*>? = null

    /**
     * The absolute URL of the listing page after [document], or null when it is the last. Null by
     * default: most listings are one page. A subclass that returns one has its later pages fetched
     * and scraped too, up to [maxOverviewPages] (ADR-007 §"Pagination — First Page Only").
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
        pipeline.run(
            HtmlImport(
                entry = HtmlImport.Entry.Conditional(cookies),
                readListing = { document, entryUrl ->
                    val overview = htmlFetcher.scrapeListingPages(eventSource, document, entryUrl, maxOverviewPages, ::nextOverviewPage, scrapeOverview)
                    HtmlImport.Listing(overview.events, overview.complete)
                },
                listedLine = { count -> "Scraped $count event(s) from ${eventSource.name} overview" },
                eventPages = HtmlImport.EventPages.Detail(scrapeDetail, ::fillGapsFromOverview, detailPageOwns, secondLanguage),
                sharedPages = sharedPages
            ),
            url,
            etag,
            lastModified
        )

    private companion object {
        const val DEFAULT_MAX_OVERVIEW_PAGES = 12
    }
}

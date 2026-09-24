package de.norm.events.scraper.monsterronsons

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import de.norm.events.scraper.scrapeListingPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Monster Ronson's Ichiban Karaoke, the Friedrichshain karaoke bar, on Webflow.
 *
 * Overview → night page:
 * 1. [HtmlFetcher] fetches `/events` conditionally (ETag / Last-Modified). The listing is paged
 * twelve nights at a time, about five weeks over four pages (#1884); the later pages are read to
 * the last, bounded by [MAX_PAGES]. Last-Modified is Webflow's site publish time, so a `304` on
 * the first page means no page changed.
 * 2. [MonsterRonsonsOverviewPageScraper] parses one event per calendar day — title, date, start
 * time, poster and host(s).
 * 3. Each night's `/posts/<slug>` page once, applying prose, door price and ticket link
 * ([MonsterRonsonsNightDetail.applyTo]).
 *
 * Implements [EventImporter] directly rather than
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] because the night page enriches the
 * card instead of superseding it: the base class treats the detail page as primary and merges
 * the overview into its gaps, the wrong way round here — the card is the only place the date is
 * stated in full, and a failed night page must not blank it. Such a page is not fatal: the date
 * keeps its card data and loses only description, price and ticket link.
 *
 * **Not published**: no doors time (one time per night, taken as the start), no presale price,
 * no genre, no lineup beyond the host in the title. The private karaoke boxes running all
 * evening appear nowhere — the listing is the main stage only.
 *
 * @see MonsterRonsonsOverviewPageScraper for the listing parsing logic.
 * @see MonsterRonsonsDetailPageScraper for the night-page parsing logic.
 * @see <a href="https://www.karaokemonster.de/events">Monster Ronson's events page</a>
 */
@Component
class MonsterRonsonsWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's year inference and past-event cutoff; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MONSTER_RONSONS

    private val overviewPageScraper = MonsterRonsonsOverviewPageScraper(clock)
    private val detailPageScraper = MonsterRonsonsDetailPageScraper()

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
                val events =
                    htmlFetcher.scrapeListingPages(
                        eventSource,
                        fetchResult.document,
                        url,
                        MAX_PAGES,
                        { document, _ -> document.nextPageUrl(NEXT_PAGE_SELECTOR) },
                        overviewPageScraper::scrape
                    )
                logger.info { "Scraped ${events.size} karaoke night(s) from Monster Ronson's listing" }

                ImportResult.Success(
                    events = enrichFromNightPages(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Fetches each distinct night page once and applies it to the events linking to it. The CMS
     * recycles entries, so two cards may link one URL, although no page did when #1884 was measured;
     * per distinct URL keeps that from re-requesting.
     */
    private suspend fun enrichFromNightPages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val nightUrls = events.map { it.sourceUrl }.distinct()
        logger.info { "Fetching ${nightUrls.size} night page(s) for ${events.size} event(s)" }

        val detailsByUrl = nightUrls.associateWith { fetchNight(it) }
        return events.map { event -> detailsByUrl[event.sourceUrl]?.applyTo(event) ?: event }
    }

    /** Fetches and parses one night page, degrading to `null` so the date keeps its card data. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken night page must not fail the whole import
    private suspend fun fetchNight(url: String): MonsterRonsonsNightDetail? =
        try {
            detailPageScraper.scrape(htmlFetcher.fetchDocument(url), url)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch night page $url, keeping listing data only" }
            null
        }

    private companion object {
        /** A runaway guard on the page walk; the listing runs to four pages. */
        const val MAX_PAGES = 8

        /** Webflow's pagination link; the query parameter name is the collection list's id. */
        const val NEXT_PAGE_SELECTOR = "a.w-pagination-next[href]"
    }
}

val MONSTER_RONSONS_LIMITATIONS =
    VenueLimitations(
        EventSource.MONSTER_RONSONS,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue states one time per night, which is taken as the start"),
        AcceptedLimitation(LimitedAspect.PRICE, "the price lives in prose and is often a time-banded tariff, which the model has no field for"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the venue bills no lineup beyond the host named in the title")
    )

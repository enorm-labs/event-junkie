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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Monster Ronson's Ichiban Karaoke, the Friedrichshain karaoke bar, on Webflow.
 *
 * The pipeline is overview → night page:
 * 1. Fetch `/events` via [HtmlFetcher] with conditional-request support (ETag / Last-Modified).
 * 2. Parse one event per calendar day via [MonsterRonsonsOverviewPageScraper] — the card carries
 *    title, date, start time, poster and the night's host(s).
 * 3. Fetch each night's `/posts/<slug>` page once and apply its prose, door price and ticket link
 *    ([MonsterRonsonsNightDetail.applyTo]).
 *
 * It implements [EventImporter] directly rather than extending
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] because the night page enriches the card
 * instead of superseding it: the base class treats the detail page as the primary source and merges
 * the overview into its gaps, which is the wrong way round here — the card is the only place the
 * date is stated in full, and a night page that fails to load must not be able to blank it.
 *
 * A night page that cannot be fetched or parsed is not fatal: that date keeps its card data and
 * loses only the description, price and ticket link.
 *
 * **What this source does not publish**: no doors time (one time per night, taken as the start), no
 * presale price, no genre, and no lineup beyond the host named in the title. The venue also runs
 * private karaoke boxes all evening, which appear nowhere in the programme — the listing describes
 * the main stage only. The window is short by design: the CMS holds roughly twelve days at a time,
 * so each import replaces a rolling window rather than accumulating a season.
 *
 * @see MonsterRonsonsOverviewPageScraper for the listing parsing logic.
 * @see MonsterRonsonsDetailPageScraper for the night-page parsing logic.
 * @see <a href="https://www.karaokemonster.de/events">Monster Ronson's events page</a>
 */
@Component
class MonsterRonsonsWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's year inference and past-event cutoff. Defaults to the system clock; override in tests. */
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
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} karaoke night(s) from Monster Ronson's listing" }

                ImportResult.Success(
                    events = enrichFromNightPages(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Fetches each distinct night page once and applies it to the event that links to it.
     *
     * The CMS recycles its entries, so two cards pointing at one page is possible even though the
     * current window has none; fetching per distinct URL keeps that case from re-requesting a page.
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
}

val MONSTER_RONSONS_LIMITATIONS =
    VenueLimitations(
        EventSource.MONSTER_RONSONS,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue states one time per night, which is taken as the start"),
        AcceptedLimitation(LimitedAspect.PRICE, "the price lives in prose and is often a time-banded tariff, which the model has no field for"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the venue bills no lineup beyond the host named in the title")
    )

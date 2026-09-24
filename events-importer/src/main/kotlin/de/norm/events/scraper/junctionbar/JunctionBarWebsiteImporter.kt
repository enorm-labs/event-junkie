package de.norm.events.scraper.junctionbar

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Junction Bar Berlin — a single source covering **both** of the venue's
 * programs (ADR-007 §"Single Entry URL": one venue, two internally-fetched page structures).
 *
 * The homepage (the configured entry URL) links to two independent programs, merged here:
 * 1. **Live music** — a `music_html/music.html` listing linking to per-month program pages
 * (`program/MM_YYYY/MM_YY.html`), each parsed by [JunctionBarMusicOverviewPageScraper].
 * 2. **DJ program** — a single `DJ_html/DJ.html` page parsed by [JunctionBarDjOverviewPageScraper].
 *
 * Each upcoming live night then gets one more request: its `junction-bar-shop.de` ticket page,
 * the only place the venue prints the price and the sold-out badge ([JunctionBarShopPageScraper]).
 * A failed shop page is not fatal: the night keeps its programme data and loses only those two
 * fields. DJ nights link no shop page and cost no extra request. Past nights are skipped, because
 * persistence drops them anyway.
 *
 * Both program URLs are discovered from the homepage's navigation (falling back to their
 * conventional relative paths), then fetched internally. Conditional requests are intentionally
 * **not** used: the entry/listing ETags change only when a new month is added, not when a band
 * is added to an existing month, so relying on them would miss mid-month edits. Every run
 * re-fetches and relies on idempotent `sourceId` upserts — [ImportResult.Success] with `null`
 * cache headers (no `NotModified` path). Past-dated nights are dropped centrally at persistence
 * (`EventUpsertService`).
 *
 * @see JunctionBarMusicOverviewPageScraper for live-music parsing.
 * @see JunctionBarDjOverviewPageScraper for DJ parsing.
 * @see JunctionBarShopPageScraper for the ticket shop's price and sold-out badge.
 * @see <a href="https://www.junction-bar.de/index.html">Junction Bar homepage</a>
 */
@Component
class JunctionBarWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the DJ scraper's year inference and the past-night cutoff on shop fetches; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.JUNCTION_BAR

    private val musicOverviewPageScraper = JunctionBarMusicOverviewPageScraper()
    private val djOverviewPageScraper = JunctionBarDjOverviewPageScraper(clock)
    private val shopPageScraper = JunctionBarShopPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val homepage = htmlFetcher.fetchDocument(url)
        val musicEvents = importLiveMusic(homepage, url)
        val djEvents = importDjProgram(homepage, url)

        val events = dedupeBySourceId(musicEvents + djEvents)
        logger.info { "Scraped ${events.size} Junction Bar event(s) (${musicEvents.size} live music, ${djEvents.size} DJ)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /** Follows the live-music listing to its monthly program pages and parses each. */
    private suspend fun importLiveMusic(
        homepage: Document,
        entryUrl: String
    ): List<ScrapedEvent> {
        val listingUrl = resolveProgramUrl(homepage, entryUrl, MUSIC_LINK_MARKER, MUSIC_LISTING_FALLBACK)
        val listing = htmlFetcher.fetchDocument(listingUrl)
        val monthUrls =
            listing
                .select("a[href]")
                .map { it.attr("href") }
                .filter { MONTH_PAGE_PATTERN.containsMatchIn(it) }
                .map { resolveUrl(listingUrl, it) }
                .distinct()
        logger.info { "Found ${monthUrls.size} monthly program page(s) linked from Junction Bar listing $listingUrl" }

        val nights =
            monthUrls.flatMap { monthUrl ->
                musicOverviewPageScraper.scrape(htmlFetcher.fetchDocument(monthUrl), monthUrl)
            }
        return enrichFromShopPages(nights)
    }

    /** Applies each upcoming night's shop page; a night without a ticket link stays as it is. */
    private suspend fun enrichFromShopPages(nights: List<ScrapedEvent>): List<ScrapedEvent> {
        val today = LocalDate.now(clock)
        return nights.map { night ->
            val shopUrl = night.ticketUrl?.takeIf { night.eventDate >= today } ?: return@map night
            fetchShopOffer(night, shopUrl)?.applyTo(night) ?: night
        }
    }

    /** Fetches and parses one shop page, degrading to `null` so the night keeps its programme data. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken shop page must not fail the whole import
    private suspend fun fetchShopOffer(
        night: ScrapedEvent,
        shopUrl: String
    ): JunctionBarShopOffer? =
        try {
            shopPageScraper.scrape(htmlFetcher.fetchDocument(shopUrl))
        } catch (e: Exception) {
            logger.at(Level.WARN) {
                message = "Failed to fetch shop page for '${night.title}', keeping it without price or sold-out state"
                cause = e
                payload = mapOf(LogFields.URL to shopUrl, LogFields.EVENT_SOURCE_ID to night.sourceId)
            }
            null
        }

    /** Fetches and parses the single DJ program page. */
    private suspend fun importDjProgram(
        homepage: Document,
        entryUrl: String
    ): List<ScrapedEvent> {
        val djUrl = resolveProgramUrl(homepage, entryUrl, DJ_LINK_MARKER, DJ_PAGE_FALLBACK)
        return djOverviewPageScraper.scrape(htmlFetcher.fetchDocument(djUrl), djUrl)
    }

    /**
     * Resolves a program page URL from the homepage nav (an `<a>` whose href contains
     * [linkMarker]), falling back to the program's conventional relative [fallbackPath].
     */
    private fun resolveProgramUrl(
        homepage: Document,
        entryUrl: String,
        linkMarker: String,
        fallbackPath: String
    ): String {
        val href = homepage.attrAt("a[href*=$linkMarker]", "href")
        return href?.let { resolveUrl(entryUrl, it) } ?: resolveUrl(entryUrl, fallbackPath)
    }

    /** Keeps the first occurrence of each `sourceId` in case the same event surfaces twice. */
    private fun dedupeBySourceId(events: List<ScrapedEvent>): List<ScrapedEvent> = events.distinctBy { it.sourceId }

    companion object {
        /** A `program/MM_YYYY/…` monthly page link on the live-music listing page. */
        private val MONTH_PAGE_PATTERN = Regex("""program/\d{2}_\d{4}/""")

        /** Homepage nav href fragment identifying the live-music listing link, and its fallback path. */
        private const val MUSIC_LINK_MARKER = "music_html"
        private const val MUSIC_LISTING_FALLBACK = "music_html/music.html"

        /** Homepage nav href fragment identifying the DJ program link, and its fallback path. */
        private const val DJ_LINK_MARKER = "DJ_html"
        private const val DJ_PAGE_FALLBACK = "DJ_html/DJ.html"
    }
}

val JUNCTION_BAR_LIMITATIONS =
    VenueLimitations(
        EventSource.JUNCTION_BAR,
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the programme is one page per month; a live night's only page of its own is its ticket-shop entry, kept as the ticket link"
        )
    )

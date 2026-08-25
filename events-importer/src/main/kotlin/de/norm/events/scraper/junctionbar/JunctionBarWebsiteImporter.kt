package de.norm.events.scraper.junctionbar

import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Junction Bar Berlin — a single source covering **both** of the venue's
 * programs (ADR-007 §"Single Entry URL": one venue, two internally-fetched page structures).
 *
 * The homepage (the configured entry URL) links to two independent programs, merged here:
 * 1. **Live music** — a `music_html/music.html` listing that links to per-month program pages
 *    (`program/MM_YYYY/MM_YY.html`), each parsed by [JunctionBarMusicOverviewPageScraper].
 * 2. **DJ program** — a single `DJ_html/DJ.html` page parsed by [JunctionBarDjOverviewPageScraper].
 *
 * Both program URLs are discovered from the homepage's navigation (falling back to their
 * conventional relative paths), then fetched internally. Conditional requests are intentionally
 * **not** used: the entry/listing ETags only change when a new month is added, not when a band is
 * added to an existing month, so relying on them would miss mid-month edits. Every run re-fetches
 * the pages and relies on idempotent `sourceId` upserts — [ImportResult.Success] is returned with
 * `null` cache headers (no `NotModified` path). Past-dated nights are dropped centrally at
 * persistence time (`EventUpsertService`).
 *
 * @see JunctionBarMusicOverviewPageScraper for live-music parsing.
 * @see JunctionBarDjOverviewPageScraper for DJ parsing.
 * @see <a href="https://www.junction-bar.de/index.html">Junction Bar homepage</a>
 */
@Component
class JunctionBarWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the DJ scraper's weekday-based year inference. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.JUNCTION_BAR

    private val musicOverviewPageScraper = JunctionBarMusicOverviewPageScraper()
    private val djOverviewPageScraper = JunctionBarDjOverviewPageScraper(clock)

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

        return monthUrls.flatMap { monthUrl ->
            musicOverviewPageScraper.scrape(htmlFetcher.fetchDocument(monthUrl), monthUrl)
        }
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
     * Resolves a program page URL from the homepage nav (an `<a>` whose href contains [linkMarker]),
     * falling back to the program's conventional relative [fallbackPath] when the link is absent.
     */
    private fun resolveProgramUrl(
        homepage: Document,
        entryUrl: String,
        linkMarker: String,
        fallbackPath: String
    ): String {
        val href = homepage.selectFirst("a[href*=$linkMarker]")?.attr("href")?.takeIf { it.isNotBlank() }
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

/** Nothing this source withholds needs declaring (#715). */
val JUNCTION_BAR_LIMITATIONS = VenueLimitations(EventSource.JUNCTION_BAR)

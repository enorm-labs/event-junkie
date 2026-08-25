package de.norm.events.scraper.havanna

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Havanna Berlin — a Squarespace Latin dance club that publishes **no dated
 * programme at all**.
 *
 * Every other scraped venue announces individual dated events; Havanna instead asserts a standing
 * weekly schedule. Its `/events` page is a static three-column teaser linking to three undated pages
 * (`/wednesday`, `/friday`, `/saturday`), each describing a resident night that runs every week: the
 * dancefloor genres, the start time, the door price. The pipeline is therefore discovery → describe →
 * **expand**:
 * 1. Fetch `/events` and read the night links via [HavannaOverviewPageScraper].
 * 2. Fetch each night page and parse it into an undated [HavannaWeeklyNight] via [HavannaDetailPageScraper].
 * 3. Expand each night into one dated event per week over a rolling horizon
 *    ([HavannaWeeklyNight.OCCURRENCE_WEEKS]).
 *
 * Because the events are derived rather than announced, two things follow. Conditional requests are
 * intentionally **not** used: these pages have not changed since 2016, so a 304 would freeze the
 * horizon and the calendar would stop advancing — every run re-fetches and relies on idempotent
 * `sourceId` upserts, returning [ImportResult.Success] with `null` cache headers (there is no
 * `NotModified` path). And a closure notice on a night page ("… AB DEM 01.07.2026 IN DER
 * SOMMERPAUSE!") suppresses that night's occurrences from the announced date on, so the derived
 * schedule doesn't keep asserting parties the venue has called off. The notice is read per page: the
 * venue posts it on the night it affects, and nothing on the site says a break extends to the others.
 *
 * A night page that fails to fetch or parse is skipped with a warning rather than failing the whole
 * import, so one broken page doesn't cost the other two nights.
 *
 * @see HavannaWeeklyNight for the recurrence expansion.
 * @see <a href="https://www.havanna-berlin.de/events">Havanna events page</a>
 */
@Component
class HavannaWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock anchoring the rolling occurrence horizon. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HAVANNA

    private val overviewPageScraper = HavannaOverviewPageScraper()
    private val detailPageScraper = HavannaDetailPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val overview = htmlFetcher.fetchDocument(url)
        val nightLinks = overviewPageScraper.scrape(overview, url)
        logger.info { "Found ${nightLinks.size} weekly night page(s) linked from Havanna $url" }

        val events = nightLinks.flatMap { scrapeNight(it) }.distinctBy { it.sourceId }
        logger.info { "Generated ${events.size} Havanna event(s) from ${nightLinks.size} weekly night(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /** Fetches one night page and expands it into its weekly occurrences, degrading to none on failure. */
    @Suppress("TooGenericExceptionCaught") // Intentional: one unreachable night page must not abort the other nights.
    private suspend fun scrapeNight(link: HavannaNightLink): List<ScrapedEvent> =
        try {
            val night = detailPageScraper.scrape(htmlFetcher.fetchDocument(link.url), link.url)
            night
                // The night pages carry their own poster; the overview teaser is the fallback.
                ?.copy(imageUrl = night.imageUrl ?: link.imageUrl)
                ?.toScrapedEvents(clock)
                .orEmpty()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to import Havanna night page ${link.url}, skipping" }
            emptyList()
        }
}

val HAVANNA_LIMITATIONS =
    VenueLimitations(
        EventSource.HAVANNA,
        AcceptedLimitation(
            LimitedAspect.EVENT_DATE,
            "the venue publishes no dated programme: its three resident nights carry only a weekday, so occurrences are generated from the weekly schedule"
        )
    )

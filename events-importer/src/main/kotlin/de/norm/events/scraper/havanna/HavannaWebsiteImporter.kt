package de.norm.events.scraper.havanna

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractFanOutImporter
import de.norm.events.scraper.AcceptedLimitation
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
 * Website importer for Havanna Berlin, a Squarespace Latin dance club that publishes no dated
 * programme: `/events` links to three undated pages (`/wednesday`, `/friday`, `/saturday`),
 * each a resident night that runs every week. So discovery ([HavannaOverviewPageScraper]),
 * describe ([HavannaDetailPageScraper] into an undated [HavannaWeeklyNight]), then expand into
 * one dated event per week over [HavannaWeeklyNight.OCCURRENCE_WEEKS]. Conditional requests are
 * not used: the pages have not changed since 2016, and a 304 would freeze the horizon, so every
 * run re-fetches and returns [ImportResult.Success] with `null` cache headers. A closure notice
 * ("… AB DEM 01.07.2026 IN DER SOMMERPAUSE!") suppresses that night's occurrences from the date
 * on, read per page since nothing says a break extends to the other nights. A failed night page
 * is skipped with a warning.
 *
 * @see HavannaWeeklyNight for the recurrence expansion.
 * @see <a href="https://www.havanna-berlin.de/events">Havanna events page</a>
 */
@Component
class HavannaWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock anchoring the rolling occurrence horizon. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) : AbstractFanOutImporter<HavannaNightLink>() {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HAVANNA

    private val overviewPageScraper = HavannaOverviewPageScraper()
    private val detailPageScraper = HavannaDetailPageScraper()

    override suspend fun inputs(url: String): List<HavannaNightLink> {
        val nightLinks = overviewPageScraper.scrape(htmlFetcher.fetchDocument(url), url)
        logger.info { "Found ${nightLinks.size} weekly night page(s) linked from Havanna $url" }
        return nightLinks
    }

    override fun failure(input: HavannaNightLink): String = "Failed to import Havanna night page ${input.url}, skipping"

    override fun combine(events: List<ScrapedEvent>): List<ScrapedEvent> = events.distinctBy { it.sourceId }

    override fun doneLine(
        events: Int,
        inputs: Int
    ): String = "Generated $events Havanna event(s) from $inputs weekly night(s)"

    /**
     * Fetches one night page and expands it into its weekly occurrences. A failed page marks the run
     * incomplete, as it would otherwise read as a cancelled weekday (#1980).
     */
    override suspend fun read(input: HavannaNightLink): List<ScrapedEvent> {
        val night = detailPageScraper.scrape(htmlFetcher.fetchDocument(input.url), input.url)
        return night
            // The night pages carry their own poster; the overview teaser is the fallback.
            ?.copy(imageUrl = night.imageUrl ?: input.imageUrl)
            ?.toScrapedEvents(clock)
            .orEmpty()
    }
}

val HAVANNA_LIMITATIONS =
    VenueLimitations(
        EventSource.HAVANNA,
        AcceptedLimitation(
            LimitedAspect.EVENT_DATE,
            "the venue publishes no dated programme: its three resident nights carry only a weekday, so occurrences are generated from the weekly schedule"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )

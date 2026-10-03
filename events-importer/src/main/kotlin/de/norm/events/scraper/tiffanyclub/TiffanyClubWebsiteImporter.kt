package de.norm.events.scraper.tiffanyclub

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Tiffany Club, a WordPress and Elementor site.
 *
 * `/upcoming-events/` lists the whole programme, months ahead, and supplies every field but the
 * blurb ([TiffanyClubOverviewPageScraper]). Each night's `/event/<slug>/` page adds that blurb
 * ([TiffanyClubDetailPageScraper]). A failed or blurb-less page keeps the listing row, flagged
 * `detailUnavailable` so the upsert keeps the stored description (see #2425). The WordPress
 * `event` REST type carries the title and nothing else, so the HTML is the source.
 *
 * @see <a href="https://tiffany-berlin.de/upcoming-events/">Tiffany Club upcoming events</a>
 */
@Component
class TiffanyClubWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Tiffany Club listing", TiffanyClubOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.TIFFANY_CLUB
    override val listsWholeProgramme: Boolean = true

    private val detailPageScraper = TiffanyClubDetailPageScraper()

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = ::addDescription

    private fun addDescription(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? = detailPageScraper.scrapeDescription(document)?.let { event.copy(description = it) }
}

val TIFFANY_CLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.TIFFANY_CLUB,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; the type is read from the title and defaults to a party"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no ticket price, only a guest-list discount inside a form"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for a night")
    )

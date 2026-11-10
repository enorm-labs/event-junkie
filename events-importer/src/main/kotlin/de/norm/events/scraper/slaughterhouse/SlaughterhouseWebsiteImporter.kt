package de.norm.events.scraper.slaughterhouse

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Slaughterhouse: the `/konzerte/` page alone, one post that lists every coming night.
 *
 * @see SlaughterhouseOverviewPageScraper for the entry blocks.
 */
@Component
class SlaughterhouseWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Slaughterhouse", SlaughterhouseOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SLAUGHTERHOUSE
    override val listsWholeProgramme: Boolean = true
}

val SLAUGHTERHOUSE_LIMITATIONS =
    VenueLimitations(
        EventSource.SLAUGHTERHOUSE,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "an entry names one time, mostly the start", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.START_TIME, "a party entry may name only doors, and a festival no time", verified = "2026-10-29"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page names a start or doors, no end", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.PRICE, "most entries name no price, and none a presale price", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.GENRE, "only a party's style line names one", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every night is one entry on the programme page", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-13")
    )

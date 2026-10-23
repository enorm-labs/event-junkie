package de.norm.events.scraper.eighthundreda

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for 800A Bar & Cabaret: the home page alone, whose `EventsSection` island carries the next three months.
 * `/events/` itself answers 404, and each event page shows the same row.
 *
 * @see EightHundredAHomePageScraper for the island's rows.
 */
@Component
class EightHundredAWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "800A Bar & Cabaret", EightHundredAHomePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.EIGHT_HUNDRED_A
}

val EIGHT_HUNDRED_A_LIMITATIONS =
    VenueLimitations(
        EventSource.EIGHT_HUNDRED_A,
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name a format, such as Music or Burlesque, not a style"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "a row has one price, the ticket price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "800A presents its own nights, and `host_name` names a promoter only for an external host"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the rows carry no sold-out flag"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "most rows name no performers, and the text bills them in free prose")
    )

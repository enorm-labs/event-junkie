package de.norm.events.scraper.kuehlspot

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Kühlspot Social Club, a hand-edited WordPress and Elementor one-pager. The home
 * page's events section is the whole programme, about a month ahead, parsed by [KuehlspotOverviewPageScraper].
 * There is no event post type and no page per night, so the WordPress REST API has nothing to add.
 *
 * The room plays one night a date, so the date is the night's identity.
 *
 * @see <a href="https://kuehlspot.com/#events">Kühlspot events</a>
 */
@Component
class KuehlspotWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Kühlspot Social Club", KuehlspotOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.KUEHLSPOT
    override val listsWholeProgramme: Boolean = true
}

val KUEHLSPOT_LIMITATIONS =
    VenueLimitations(
        EventSource.KUEHLSPOT,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line, a title and a line-up, no second title line"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site states a curfew for musicians, not an end per night"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the events section carries no pictures"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "admission is a donation at the door and nothing is sold online"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "admission is a donation at the door and nothing is sold in advance"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "admission is a sliding-scale donation, kept as a price note"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the home page")
    )

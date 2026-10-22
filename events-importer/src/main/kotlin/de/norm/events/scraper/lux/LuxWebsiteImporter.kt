package de.norm.events.scraper.lux

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for L.U.X, a hand-written static site. The home page lists the whole
 * programme, two or three weeks ahead, with no API behind it ([LuxOverviewPageScraper]).
 *
 * The bar holds one night a date and the site has no page per night, so the date is the night's identity.
 *
 * @see <a href="https://www.lux-berlin.net/">L.U.X</a>
 */
@Component
class LuxWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the L.U.X programme", LuxOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.LUX
    override val listsWholeProgramme: Boolean = true
}

val LUX_LIMITATIONS =
    VenueLimitations(
        EventSource.LUX,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a DJ night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one time per night, marked as doors only on some nights"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time beyond an open-ended late"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets and links no ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style beyond a free-text tagline"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "entry is free or a donation, and there is no presale"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter apart from free text"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the home page")
    )

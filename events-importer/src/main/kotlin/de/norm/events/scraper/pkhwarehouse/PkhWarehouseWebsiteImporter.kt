package de.norm.events.scraper.pkhwarehouse

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
 * Website importer for PKH Warehouse, a hand-written static site. The home page lists the whole
 * programme with no API behind it ([PkhWarehouseOverviewPageScraper]). The club copies its nights
 * by hand from its Resident Advisor page, which this importer never reads.
 *
 * Past nights can stay on the page; the upsert drops them. The club holds one night a date and
 * the site has no page per night, so the date is the night's identity.
 *
 * @see <a href="https://www.pkh.events/">PKH Warehouse</a>
 */
@Component
class PkhWarehouseWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the PKH Warehouse programme", PkhWarehouseOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.PKH_WAREHOUSE
    override val listsWholeProgramme: Boolean = true
}

val PKH_WAREHOUSE_LIMITATIONS =
    VenueLimitations(
        EventSource.PKH_WAREHOUSE,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a club night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one opening time per night"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the site shows no picture per night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names a style only in free text on some nights"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the site links one ticket shop for the whole club, and a night's own shop only rarely"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names crews only in free text beside the theme"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the home page")
    )

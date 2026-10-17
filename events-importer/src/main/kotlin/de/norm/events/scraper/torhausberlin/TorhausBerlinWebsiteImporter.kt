package de.norm.events.scraper.torhausberlin

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Torhaus Berlin, a Next.js and Payload CMS site whose events come from a Teamup calendar.
 *
 * One fetch per import: the German start page `/de` renders every upcoming event as a card with 24-hour clocks, parsed
 * by [TorhausBerlinOverviewPageScraper]. The cards link to no page of their own. The `www.` host answers with a Vercel
 * checkpoint, so the source URL names the bare host, which serves the page directly. The cooking collective's monthly
 * Küfa is a food row and is left out. A night with the `Radio` badge is a THF Radio DJ broadcast from the bar.
 *
 * @see <a href="https://torhausberlin.de/de">Torhaus Berlin</a>
 */
@Component
class TorhausBerlinWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Torhaus Berlin", TorhausBerlinOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.TORHAUS_BERLIN
}

val TORHAUS_BERLIN_LIMITATIONS =
    VenueLimitations(
        EventSource.TORHAUS_BERLIN,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the event cards link to no page of their own"),
        AcceptedLimitation(LimitedAspect.SUBTITLE, "a card carries only a title and the times"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "a card carries only a title and the times"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the event cards show no image"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the site sells no tickets"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes one opening time per event"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the show name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for an event")
    )

package de.norm.events.scraper.backsteinboot

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Backsteinboot, a Cargo site with no API. The landing page lists the current
 * month's programme in server-rendered HTML, with no page per event ([BacksteinbootProgrammePageScraper]).
 *
 * The page lists the month, not the whole programme, so the stale cleanup stays at the scrape's last date.
 * Most entries are workshops, which the scraper drops. Date plus title is the identity, because a monthly
 * talk repeats its title.
 *
 * @see <a href="https://backsteinboot.org/program-(landing-page)-1">Backsteinboot programme</a>
 */
@Component
class BacksteinbootWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Backsteinboot programme", BacksteinbootProgrammePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.BACKSTEINBOOT
}

val BACKSTEINBOOT_LIMITATIONS =
    VenueLimitations(
        EventSource.BACKSTEINBOOT,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each entry has one title line"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the programme prints only credits and links, no text about an event"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per event"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names a format, never a musical style"),
        AcceptedLimitation(LimitedAspect.PRICE, "most entries say donation or invitation, and few print a figure"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "only some club nights link a ticket shop"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled event can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per event, so every event points at the programme")
    )

package de.norm.events.scraper.kiezkapelle

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Kiezkapelle, a WordPress site on Lay Theme. `/programm/` holds the
 * upcoming entries and an archive on one hand-laid page ([KiezkapelleProgrammPageScraper]).
 * No events plugin runs: the REST API serves only posts and pages, and the page is one `page`.
 *
 * The page points to Instagram for the current programme, so it lists few entries. An entry has
 * no page of its own; the date and the title slug are its identity.
 */
@Component
class KiezkapelleWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Kiezkapelle programme", KiezkapelleProgrammPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.KIEZKAPELLE
}

val KIEZKAPELLE_LIMITATIONS =
    VenueLimitations(
        EventSource.KIEZKAPELLE,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "an entry has a presenter line and a title, no second title line"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category per entry"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one time range per entry"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "registration and tickets are named only in the entry's prose"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per entry"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only in the entry's prose"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site names performers only in the entry's prose"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled entry can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per entry, so every entry points at the programme")
    )

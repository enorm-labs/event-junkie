package de.norm.events.scraper.speakeazy

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Speakeazy's Squarespace events page.
 *
 * `/events` lists every upcoming night with its whole record, so no event page is fetched. The
 * collection's `?format=json` is disallowed by `robots.txt`, so the HTML is the source.
 *
 * @see SpeakeazyOverviewPageScraper for the parsing.
 * @see <a href="https://www.speakeazyberlin.de/events">Speakeazy programme</a>
 */
@Component
class SpeakeazyWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Speakeazy", SpeakeazyOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SPEAKEAZY
    override val listsWholeProgramme: Boolean = true
}

val SPEAKEAZY_LIMITATIONS =
    VenueLimitations(
        EventSource.SPEAKEAZY,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing prints a start and an end time and no doors time"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue sells at the door and links no ticket shop"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the venue prints only the box-office price"),
        AcceptedLimitation(LimitedAspect.GENRE, "the listing names no style, only the blurb describes one"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )

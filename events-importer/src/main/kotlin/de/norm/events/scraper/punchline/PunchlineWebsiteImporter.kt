package de.norm.events.scraper.punchline

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the PUNCH L!NE Club at Potsdamer Platz. Its ticket page lists every date,
 * about a year ahead, in one Next.js flight payload, so one fetch reads the programme.
 *
 * @see PunchlineTicketsPageScraper for the payload and the field mapping.
 * @see <a href="https://punchlineberlin.com/de/tickets">PUNCH L!NE tickets</a>
 */
@Component
class PunchlineWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "PUNCH L!NE", PunchlineTicketsPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PUNCHLINE
}

val PUNCHLINE_LIMITATIONS =
    VenueLimitations(
        EventSource.PUNCHLINE,
        AcceptedLimitation(LimitedAspect.IMAGE, "the date list carries no image"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the date list links no ticket shop; tickets sell on Ticketmaster"),
        AcceptedLimitation(LimitedAspect.PRICE, "the club publishes no price; Ticketmaster states it"),
        AcceptedLimitation(LimitedAspect.GENRE, "the date list names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the date list names no promoter")
    )

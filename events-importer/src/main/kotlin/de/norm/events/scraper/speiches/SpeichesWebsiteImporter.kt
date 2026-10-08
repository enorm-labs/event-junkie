package de.norm.events.scraper.speiches

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Speiches Rock- und Blueskneipe, whose programme is a table on rockradio.de,
 * the internet radio that broadcasts from the pub. The pub's own site shows it in an iframe.
 *
 * One page lists every booked night, about three months ahead, so one request per import. Each night
 * has a page on rockradio.de, but it repeats the row and adds only the anniversary motto, so none is fetched.
 *
 * @see SpeichesOverviewPageScraper for the parsing.
 */
@Component
class SpeichesWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Speiche", SpeichesOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.SPEICHES
    override val listsWholeProgramme: Boolean = true
}

val SPEICHES_LIMITATIONS =
    VenueLimitations(
        EventSource.SPEICHES,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "each night has one line of text, read as title and subtitle"),
        AcceptedLimitation(LimitedAspect.PRICE, "entry is always free and no price is printed"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a pub with free entry sells no tickets"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the table prints one time per night"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the open stage and the radio broadcasts name no act in a form that can be read"),
        houseGenre = "Blues"
    )

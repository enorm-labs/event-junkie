package de.norm.events.scraper.ballhausberlin

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Ballhaus Berlin: the `/de/termine/` page alone, which lists every date since 2019.
 *
 * The site's `robots.txt` answers HTTP 500, which RFC 9309 reads as a complete disallow, so the seed has no source for the
 * venue yet (EVENT_DATA_SOURCES.md, Blocked). The importer is ready for the day it answers 200 or 404.
 *
 * @see BallhausBerlinTerminePageScraper for the list's markup.
 */
@Component
class BallhausBerlinWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Ballhaus Berlin", BallhausBerlinTerminePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.BALLHAUS_BERLIN
    override val listsWholeProgramme: Boolean = true
}

val BALLHAUS_BERLIN_LIMITATIONS =
    VenueLimitations(
        EventSource.BALLHAUS_BERLIN,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the list has no category, so the house's own words type a night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the list names no music style"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the title names the night or the company, and the text bills the acts in prose"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "a date has no page of its own"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the list carries no sold-out mark"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the list names no organiser")
    )

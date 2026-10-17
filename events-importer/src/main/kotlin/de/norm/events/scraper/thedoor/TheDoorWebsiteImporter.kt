package de.norm.events.scraper.thedoor

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for The Door, a WordPress site built with Oxygen. `/events/` renders the current month's Fridays
 * and Saturdays as a weekly grid on one page ([TheDoorEventsPageScraper]).
 *
 * The site has an `event01` post type, but its REST record carries only the date: title, lineup and flyer are ACF
 * fields the API does not expose, and the post's permalink redirects to the homepage. So the grid is read, and the
 * date is the night's identity, since the club opens one night a date.
 *
 * The page shows one month and nothing says how early it turns to the next, so [listsWholeProgramme] stays false.
 *
 * @see <a href="https://thedoor.club/events/">The Door events</a>
 */
@Component
class TheDoorWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "The Door's events page", TheDoorEventsPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.THE_DOOR
}

val THE_DOOR_LIMITATIONS =
    VenueLimitations(
        EventSource.THE_DOOR,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a title and a lineup, no second title line"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the grid prints no text about a night"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a DJ night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry is at the door; the site sells only table reservations"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the grid has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the event permalinks redirect to the homepage, so every night points at the grid"),
        houseGenre = "House"
    )

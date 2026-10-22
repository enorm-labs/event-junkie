package de.norm.events.scraper.eightmm

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for 8MM's Squarespace `/program` calendar.
 *
 * The calendar renders in the browser, but the page carries the shown month's events in a `<noscript>` list
 * ([EightMmProgramPageScraper]). Each upcoming event's own page adds the blurb and the doors and show times
 * ([EightMmEventPageScraper]). `robots.txt` disallows `?format=json`, `?format=ical` and `?month=`, so the next
 * month's events arrive only once the calendar shows that month.
 *
 * @see <a href="https://www.8mmbar.de/program">8MM programme</a>
 */
@Component
class EightMmWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "8MM", EightMmProgramPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.EIGHT_MM
    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = EightMmEventPageScraper()::enrich
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.START_TIME)
}

val EIGHT_MM_LIMITATIONS =
    VenueLimitations(
        EventSource.EIGHT_MM,
        AcceptedLimitation(LimitedAspect.PAGINATION, "the calendar lists only the month it shows, and robots.txt bars the other months"),
        AcceptedLimitation(LimitedAspect.END_TIME, "every calendar entry ends one hour after it starts, a placeholder rather than a real end"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "only a concert blurb states a doors time; a DJ night has none"),
        AcceptedLimitation(LimitedAspect.SUBTITLE, "an event carries a title and a blurb, no second title line"),
        AcceptedLimitation(LimitedAspect.GENRE, "nothing on the calendar or the event pages names a style"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a DJ night bills nobody from its free-form title, except a Name (Band) … DJ Residency title"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site states no price; bands keep the door money"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a blurb points to the venue's Instagram for tickets, never to a shop"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "nothing flags an event sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "nothing flags an event cancelled"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "a guest promoter is named only inside the title or the blurb")
    )

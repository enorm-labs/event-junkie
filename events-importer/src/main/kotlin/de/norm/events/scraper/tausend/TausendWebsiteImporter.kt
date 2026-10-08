package de.norm.events.scraper.tausend

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.SecondLanguageListing
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Bar Tausend's lineup, one WordPress/Divi page with every announced night inline.
 *
 * The English lineup `/en/lineup/` has the same blocks and timestamps with the text translated, so it is
 * read for the second language: one more page per run. Each night's link goes to Resident Advisor and is
 * stored as the ticket URL, never fetched.
 *
 * @see TausendOverviewPageScraper for the parsing.
 * @see <a href="https://tausendberlin.com/lineup/">Bar Tausend lineup</a>
 */
@Component
class TausendWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Bar Tausend", TausendOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.TAUSEND
    override val listsWholeProgramme: Boolean = true
    override val secondLanguage = SecondLanguageListing("en", TausendOverviewPageScraper()::descriptions)
}

val TAUSEND_LIMITATIONS =
    VenueLimitations(
        EventSource.TAUSEND,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every night points at its anchor on the lineup page"),
        AcceptedLimitation(LimitedAspect.PRICE, "the lineup prints no admission"),
        AcceptedLimitation(LimitedAspect.GENRE, "each night names its styles only in its prose"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page prints no closing time, and its schema.org end is 3 am on every night"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a night links its Resident Advisor page only once that page is up")
    )

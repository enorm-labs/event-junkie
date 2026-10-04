package de.norm.events.scraper.kitkatclub

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for KitKatClub's programme page, a hand-built table CMS with no feed or schema.org data.
 *
 * The homepage is prose that links each series' own site; `/Home/Club/Index.html` is the programme, and the
 * page declares UTF-8 in `<meta>` around Latin-1 static text, so Jsoup's detection keeps the rows readable.
 *
 * @see KitKatClubProgrammePageScraper for the parsing.
 * @see <a href="https://kitkatclub.org/Home/Club/Index.html">KitKatClub programme</a>
 */
@Component
class KitKatClubWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "KitKatClub", KitKatClubProgrammePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.KITKATCLUB
    override val listsWholeProgramme: Boolean = true
}

val KITKATCLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.KITKATCLUB,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every night points at the programme page"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme prints one opening time per night; only the weekend notes add a start after a warm-up"),
        AcceptedLimitation(LimitedAspect.PRICE, "the club prints no admission and refers to each night's organiser"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme rows carry no flyer"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the programme names each series but not the collective behind it"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a night links tickets only where its notes carry a Resident Advisor URL"),
        houseGenre = "Techno, House"
    )

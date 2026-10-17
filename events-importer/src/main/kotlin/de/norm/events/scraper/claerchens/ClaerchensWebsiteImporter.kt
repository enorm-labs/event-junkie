package de.norm.events.scraper.claerchens

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Clärchens Ballhaus, a WordPress site with the Pie Calendar plugin. The
 * `/programm/` page carries every calendar post as JSON in its inline FullCalendar script, past
 * occurrences and the weekly nights included, so one conditional fetch reads the whole programme
 * ([ClaerchensOverviewPageScraper]). The page prints the same list twice, once for the calendar and
 * once as the recurring set it sends to its AJAX endpoint; the scraper reads the first.
 *
 * Most of the calendar is taken part in rather than watched: dance courses, workshops, a weekly
 * house tour, a sing-along choir, a wine dinner. [isProgramme] drops these (EVENT_SCOPE.md §3.2).
 * The `/en/` path is a TranslatePress copy and is not read as a second language.
 *
 * @see <a href="https://claerchensball.haus/programm/">Clärchens Ballhaus programme</a>
 */
@Component
class ClaerchensWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Clärchens Ballhaus programme", ClaerchensOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.CLAERCHENS_BALLHAUS

    override val listsWholeProgramme: Boolean = true
}

val CLAERCHENS_BALLHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.CLAERCHENS_BALLHAUS,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each calendar entry has one title line"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the calendar names no category; the title decides the type"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the calendar's end field is a placeholder, often before the start"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no style"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the one outside link is a ticket shop on some entries and an info page on others"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the calendar bills most nights by their series name, not by their acts"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the calendar credits no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the calendar states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the calendar has no status field, so a cancelled night can only disappear")
    )

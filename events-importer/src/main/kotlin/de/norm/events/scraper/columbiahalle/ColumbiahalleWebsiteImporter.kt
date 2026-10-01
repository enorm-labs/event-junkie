package de.norm.events.scraper.columbiahalle

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Columbiahalle Berlin's Contao event listing.
 *
 * The whole programme is on `/veranstaltungen.html` with every field inline — times, prices,
 * promoter, ticket link, poster and the untruncated blurb — so no detail fetch. The cards'
 * "Kalender-Eintrag" links look like detail pages (`veranstaltung/<alias>.html`) but serve an
 * **iCal download** carrying strictly less than the listing, so they are not followed.
 * [HtmlFetcher] fetches the page conditionally (the site sends neither ETag nor Last-Modified,
 * so every run is a full fetch of one page), [ColumbiahalleOverviewPageScraper] parses it.
 *
 * @see ColumbiahalleOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.columbiahalle.berlin/veranstaltungen.html">Columbiahalle Berlin</a>
 */
@Component
class ColumbiahalleWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Columbiahalle", ColumbiahalleOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.COLUMBIAHALLE
    override val listsWholeProgramme: Boolean = true
}

val COLUMBIAHALLE_LIMITATIONS =
    VenueLimitations(
        EventSource.COLUMBIAHALLE,
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the venue's own iCal export keys the event on the same Contao id and points back at the listing anchor"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the listing names support, promoter, times and prices, and no musical style")
    )

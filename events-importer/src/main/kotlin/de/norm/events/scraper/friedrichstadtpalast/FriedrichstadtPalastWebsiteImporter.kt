package de.norm.events.scraper.friedrichstadtpalast

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Friedrichstadt-Palast's guest shows, a hand-built WordPress theme with no
 * event API. `/gastspiele/` lists every guest show with one ticket button per performance on one page
 * ([FriedrichstadtPalastGastspielePageScraper]).
 *
 * The house's own Grand Show and Young Show are not read. Each runs almost daily for a season, with
 * 220 performances of one revue from October to July, so it is a run rather than an evening programme.
 * The Young Show is a family show. A guest show has no page of its own, so its post anchor
 * (`<a name="99147">`) plus the date is its identity.
 *
 * @see <a href="https://www.palast.berlin/gastspiele/">Friedrichstadt-Palast guest shows</a>
 */
@Component
class FriedrichstadtPalastWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(
        htmlFetcher,
        "the Friedrichstadt-Palast guest shows",
        FriedrichstadtPalastGastspielePageScraper()::scrape
    ) {
    override val eventSource: EventSource = EventSource.FRIEDRICHSTADTPALAST
}

val FRIEDRICHSTADTPALAST_LIMITATIONS =
    VenueLimitations(
        EventSource.FRIEDRICHSTADTPALAST,
        AcceptedLimitation(LimitedAspect.ARTISTS, "a tribute evening is billed by its genre and names none of its performers"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each ticket button prints one start time"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page prints no end time"),
        AcceptedLimitation(LimitedAspect.GENRE, "the page names no style per show"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices sit with each outside promoter's ticket shop, not on the page"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the page states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the page has no status field, so a cancelled show can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "a guest show has no page of its own, only an anchor on the list")
    )

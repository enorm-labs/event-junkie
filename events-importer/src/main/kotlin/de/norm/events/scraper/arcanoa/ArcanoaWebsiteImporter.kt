package de.norm.events.scraper.arcanoa

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Arcanoa Berlin's 1990s `veranst.htm` programme page.
 *
 * The whole programme — currently three months — is one hand-coded page with no detail pages,
 * so a single fetch: [HtmlFetcher] fetches `veranst.htm` conditionally — the host is one of the
 * few still serving a strong `ETag` *and* a `Last-Modified`, and the page changes only when
 * the programme is edited, so 304s are reliable and frequent — and
 * [ArcanoaOverviewPageScraper] parses every dated line.
 *
 * The configured source URL must point at `veranst.htm`; `index.htm` is a frameset landing
 * page with no event data.
 *
 * @see ArcanoaOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.ssi-media.com/arcanoa/veranst.htm">Arcanoa programme</a>
 */
@Component
class ArcanoaWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Arcanoa", ArcanoaOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.ARCANOA
    override val listsWholeProgramme: Boolean = true
}

val ARCANOA_LIMITATIONS =
    VenueLimitations(
        EventSource.ARCANOA,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page"),
        AcceptedLimitation(LimitedAspect.PRICE, "a night is one line — a date, the act and a genre string — and the page prints no figure anywhere"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry is paid at the door, and the page's only links point at partner sites"),
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the style tail runs genre words together with support acts and notes, so only the words the vocabulary knows become the genre"
        ),
        AcceptedLimitation(LimitedAspect.IMAGE, "the page carries no image element at all"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the one line per night is the whole entry, with no blurb after it"),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "a night is one line with no separator between the act and the night's name, so a billing like `Arcana A Night Of Flow` cannot be split"
        )
    )

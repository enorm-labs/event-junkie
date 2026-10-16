package de.norm.events.scraper.kreuzwerk

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.ritterbutzke.RitterButzkeDetailPageScraper
import de.norm.events.scraper.ritterbutzke.RitterButzkeOverviewPageScraper
import org.springframework.stereotype.Component

/**
 * Website importer for KREUZWERK. Its site is the Ritter Butzke build, so this class reuses that
 * venue's scrapers with its own [EventSource].
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` listing conditionally — title, date, poster.
 * 2. Each `/event/DDMMYY-<Name>` page adds the `ab HH:mm` start, the ticket link, the lineup and the blurb.
 *
 * The detail pages also carry a schema.org `MusicEvent`. Only its `performer` list is read: it holds
 * no time, and its `url` points at a `/events/<slug>` path that does not exist.
 *
 * @see de.norm.events.scraper.ritterbutzke.RitterButzkeWebsiteImporter for the sister site.
 * @see <a href="https://kreuzwerk.club/events">KREUZWERK event listing</a>
 */
@Component
class KreuzwerkWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(
        htmlFetcher,
        RitterButzkeOverviewPageScraper(EventSource.KREUZWERK)::scrape,
        RitterButzkeDetailPageScraper(EventSource.KREUZWERK, readsDescription = true, lineupFromJsonLd = true)::scrape
    ) {
    override val eventSource: EventSource = EventSource.KREUZWERK
    override val listsWholeProgramme: Boolean = true
}

val KREUZWERK_LIMITATIONS =
    VenueLimitations(
        EventSource.KREUZWERK,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club publishes no categories; every night is a DJ programme"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each event page prints one opening time, `ab HH:mm`, which is stored as the start"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the club sells through a third party and prints no figure; a night is flagged free only when its title says so"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the listing names no collective apart from the night's title"),
        houseGenre = "Techno, House"
    )

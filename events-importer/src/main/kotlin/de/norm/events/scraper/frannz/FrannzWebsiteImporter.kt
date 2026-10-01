package de.norm.events.scraper.frannz

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Frannz Club Berlin's WordPress homepage event listing.
 *
 * All upcoming events render server-side on one page with full details inline (times, prices,
 * promoter, image, description) — no detail-page fetch: [HtmlFetcher] fetches `/`
 * conditionally (ETag / Last-Modified), [FrannzOverviewPageScraper] parses it.
 *
 * @see FrannzOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://frannz.eu/">Frannz Club Berlin</a>
 */
@Component
class FrannzWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Frannz", FrannzOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.FRANNZ
    override val listsWholeProgramme: Boolean = true
}

val FRANNZ_LIMITATIONS =
    VenueLimitations(
        EventSource.FRANNZ,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "nothing on the site links a `/events/<slug>/` page"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "most nights name the ticket seller instead of a figure; only the venue's own party nights carry a structured Abendkasse item, which is read"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue tags each event only with a type (Konzert, Party, Lesung), never a genre")
    )

package de.norm.events.scraper.maaya

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for MAAYA Berlin, the Afro-diasporic cultural venue and open-air pool on the
 * RAW-Gelände.
 *
 * The whole published programme is the **NEXT DATES** section of its WordPress home page, no
 * per-event pages, so one request per cycle: [HtmlFetcher] fetches it conditionally (ETag /
 * Last-Modified), [MaayaOverviewPageScraper] parses the section.
 *
 * Cloudflare fronts the site and answers some non-browser clients with a 403 challenge — curl
 * is blocked outright — but serves the JVM HTTP stack normally. A sudden run of `HTTP 403`
 * failures here means the bot rules tightened, not that the page moved.
 *
 * The listing is short by nature: about two weeks ahead, a dozen or so events. The venue's
 * real horizon, not a parsing limit — the rest goes through its newsletter and Instagram, as
 * the section's own footnote says.
 *
 * @see MaayaOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://maaya.de/">MAAYA Berlin</a>
 */
@Component
class MaayaWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's year inference on a year-less date; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "MAAYA", MaayaOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.MAAYA
}

val MAAYA_LIMITATIONS =
    VenueLimitations(
        EventSource.MAAYA,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the programme is one hand-built section of the WordPress home page"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the programme is one hand-built section of the home page and carries no detail text"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes an entry note in words and no numeric price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "there is no lineup field, and the titles are series and party names rather than acts"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no doors time"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the programme carries a name, a date and a time and no category; the type comes from a title keyword, else OTHER"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre; every music night takes the house's Afrobeats, Latin"),
        houseGenre = "Afrobeats, Latin"
    )

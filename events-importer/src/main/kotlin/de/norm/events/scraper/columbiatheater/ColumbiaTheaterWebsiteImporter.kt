package de.norm.events.scraper.columbiatheater

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Columbia Theater Berlin's homepage programme.
 *
 * A WordPress concert hall with no JSON-LD and the REST API disabled site-wide (every
 * `/wp-json/` route answers `401 rest_disabled`), so two HTML pages:
 * 1. The homepage — which *is* the full upcoming listing — via [HtmlFetcher] with
 * conditional-request support (the site sends neither ETag nor Last-Modified, so every run is
 * a full fetch).
 * 2. [ColumbiaTheaterOverviewPageScraper] parses the cards — discovery list, date, title,
 * tour/support subtitle, poster, status and lineup.
 * 3. Per event, its `/event/YYYYMMDD-<slug>/` detail page via [ColumbiaTheaterDetailPageScraper]
 * — doors/start, description, ticket URL and media presenters.
 *
 * @see ColumbiaTheaterOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see ColumbiaTheaterDetailPageScraper for detail parsing (times, blurb, tickets, presenters).
 * @see <a href="https://columbia-theater.de/">Columbia Theater Berlin</a>
 */
@Component
class ColumbiaTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, ColumbiaTheaterOverviewPageScraper()::scrape, ColumbiaTheaterDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.COLUMBIA_THEATER
    override val listsWholeProgramme: Boolean = true
}

val COLUMBIA_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.COLUMBIA_THEATER,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure; tickets are sold through an Eventim link"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre or category; style appears only in the description")
    )

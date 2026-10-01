package de.norm.events.scraper.mikropol

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Mikropol Berlin's Events-Manager concert listing.
 *
 * WordPress/Events-Manager with no JSON-LD and the REST API not exposed for anonymous reads,
 * so two HTML pages:
 * 1. [HtmlFetcher] fetches `/events/` conditionally (ETag / Last-Modified).
 * 2. [MikropolOverviewPageScraper] parses the cards — discovery list, date, start/doors times,
 * status, sold-out flag, and headliner/support artists.
 * 3. Each detail page via [MikropolDetailPageScraper] — description, image, Eventim ticket URL and door price.
 *
 * @see MikropolOverviewPageScraper for overview parsing (date, status, artists, fallback).
 * @see MikropolDetailPageScraper for detail parsing (description, image, ticket URL, door price).
 * @see <a href="https://mikropol-berlin.de/events/">Mikropol Berlin</a>
 */
@Component
class MikropolWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, MikropolOverviewPageScraper()::scrape, MikropolDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MIKROPOL
    override val listsWholeProgramme: Boolean = true
}

val MIKROPOL_LIMITATIONS =
    VenueLimitations(
        EventSource.MIKROPOL,
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style; its only category is Konzert or Club"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no figure; tickets are sold through a Dice link")
    )

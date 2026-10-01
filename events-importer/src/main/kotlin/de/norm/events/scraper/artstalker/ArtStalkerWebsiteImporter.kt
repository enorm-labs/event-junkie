package de.norm.events.scraper.artstalker

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * ART Stalker's programme lives only in its Reservix white-label shop; the venue's own site links
 * there and lists nothing. The shop's `robots.txt` disallows `/events` and its later pages, so the
 * source URL is the shop root `/`, which serves the same first 25 events and is allowed. Each card
 * is followed to its event page, which robots also allows, for the blurb, doors and prices.
 *
 * The shop's plain-curl `User-Agent` gets a CloudFront 403; the scraper's own `User-Agent` does not.
 * The listing owns the type and the artists, read from the title, so the default merge keeps them.
 */
@Component
class ArtStalkerWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.ART_STALKER

    private val overviewPageScraper = ArtStalkerOverviewPageScraper()
    private val detailPageScraper = ArtStalkerDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)
}

val ART_STALKER_LIMITATIONS =
    VenueLimitations(
        EventSource.ART_STALKER,
        AcceptedLimitation(LimitedAspect.PAGINATION, "robots.txt disallows the shop's paged listing, so only the first 25 events are read"),
        AcceptedLimitation(LimitedAspect.GENRE, "the style is only a free-text tagline after the act's name"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )

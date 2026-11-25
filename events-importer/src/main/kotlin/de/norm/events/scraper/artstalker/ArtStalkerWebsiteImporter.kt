package de.norm.events.scraper.artstalker

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * ART Stalker sells through a Reservix white-label shop; the venue's own site links there and lists
 * nothing. The shop's `robots.txt` disallows `/events`, and its root redirects there, so the shop's
 * listing is never read (#5496). The source is the venue's page on `www.reservix.de`, which robots
 * allows, 8 events to a page. Each card is followed to its event page there, which robots also allows,
 * for the blurb, doors and prices. The venue's own shop lacks the nights another organiser sells, such
 * as Kiezpoeten's, so an event's link is never moved to it.
 *
 * Plain curl gets a CloudFront 403 from the shop; the scraper's own `User-Agent` does not.
 * The listing owns the type and the artists, read from the title, so the default merge keeps them.
 */
@Component
class ArtStalkerWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(
        htmlFetcher,
        { document, _ -> OVERVIEW_SCRAPER.scrape(document) },
        ArtStalkerDetailPageScraper()::scrape
    ) {
    override val eventSource: EventSource = EventSource.ART_STALKER

    /** The last page still links a next one, disabled, which serves the last page again. */
    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? = document.nextPageUrl(".button-navigation-icon:not(.button--disabled)[href$=/${pageNumber(url) + 1}]")

    private fun pageNumber(url: String): Int =
        PAGE_NUMBER
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 1

    private companion object {
        /** The page after the venue's number: `/berlin/venue/art-stalker/v41370/2`; the first page has none. */
        val PAGE_NUMBER = Regex("""/v\d+/(\d+)$""")
    }
}

val ART_STALKER_LIMITATIONS =
    VenueLimitations(
        EventSource.ART_STALKER,
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the style is only a free-text tagline after the act's name, so only the genre words in it become the genre",
            verified = "2026-10-03"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-14"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the weekly open session is a concert that bills no act", verified = "2026-10-14")
    )

private val OVERVIEW_SCRAPER = ArtStalkerOverviewPageScraper()

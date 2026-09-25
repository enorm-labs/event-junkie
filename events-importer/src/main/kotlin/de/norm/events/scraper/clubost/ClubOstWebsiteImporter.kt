package de.norm.events.scraper.clubost

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
 * Website importer for Club OST's Django homepage: fetch the homepage via [HtmlFetcher] with
 * conditional headers, parse every card via [ClubOstOverviewPageScraper], the primary source
 * (date, start, flyer, ticket link), then fetch each detail page via [ClubOstDetailPageScraper]
 * for the title the listing upper-cases and the end time it lacks. The usual precedence is
 * inverted: the detail page is a stub, so [fillGapsFromOverview] keeps the overview's values for
 * everything but the title and the end; reading it as primary the way
 * [cassiopeia][de.norm.events.scraper.cassiopeia] does would trade a flyer and ticket link for
 * blanks.
 *
 * @see ClubOstOverviewPageScraper for the listing parse (and the site's bilingual rendering)
 * @see ClubOstDetailPageScraper for the detail parse and what the stub does not carry
 * @see <a href="https://clubost.de/">Club OST homepage</a>
 */
@Component
class ClubOstWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.CLUB_OST

    private val overviewPageScraper = ClubOstOverviewPageScraper()
    private val detailPageScraper = ClubOstDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Merges the detail page's [primary] data over the overview's [fallback]. The card wins
     * everywhere but the title: it alone carries the flyer and the ticket link. The detail page's
     * description is taken when present, the placeholder being mapped to null already.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        fallback.copy(
            title = primary.title,
            description = primary.description ?: fallback.description,
            // The end is on the detail page alone (#1408).
            endDate = primary.endDate,
            endTime = primary.endTime
        )
}

val CLUB_OST_LIMITATIONS =
    VenueLimitations(
        EventSource.CLUB_OST,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the venue programmes through Resident Advisor and leaves the CMS description empty on every event"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the listing carries no category; every card is a flyer, a title, a start time and a ticket link"),
        AcceptedLimitation(LimitedAspect.GENRE, "the listing carries no genre; every night takes the club's Techno default"),
        AcceptedLimitation(LimitedAspect.PRICE, "the listing carries no price; tickets are sold on Resident Advisor"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the listing carries no lineup, though the CMS holds an empty div where one would go"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing carries one time per night and no doors time")
    )

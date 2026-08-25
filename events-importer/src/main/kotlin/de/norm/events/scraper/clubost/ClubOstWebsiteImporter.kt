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
 * Website importer for Club OST's Django-built homepage programme.
 *
 * Orchestrates the fetch → parse pipeline:
 * 1. Fetches the homepage — which *is* the programme page — via [HtmlFetcher] with
 *    conditional-request support (ETag / Last-Modified).
 * 2. Parses every event card via [ClubOstOverviewPageScraper]. This is the **primary** source
 *    for the whole event: date, start time, flyer and Resident Advisor ticket link.
 * 3. Fetches each event's detail page and parses it via [ClubOstDetailPageScraper], for the
 *    one thing the listing gets wrong — the title, which the listing template upper-cases.
 *
 * The usual detail-page precedence is therefore **inverted here**: the detail page is a stub
 * that repeats the listing's date and time and publishes nothing else, so
 * [fillGapsFromOverview] keeps the overview's values for every field except the title. Reading
 * it as "primary" the way [cassiopeia][de.norm.events.scraper.cassiopeia] does would trade a
 * populated flyer and ticket link for the stub's blanks.
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
     * Merges the detail page's [primary] data over the overview's [fallback] data.
     *
     * The overview card is the richer of the two, so it wins everywhere except the title: it
     * alone carries the flyer and the Resident Advisor ticket link, and it states the same date
     * and start time the stub does. Only the detail page's description — a real one, should the
     * venue ever fill the field in, since the placeholder is already mapped to null — is taken
     * when present, alongside the correctly-cased title.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        fallback.copy(
            title = primary.title,
            description = primary.description ?: fallback.description
        )
}

val CLUB_OST_LIMITATIONS =
    VenueLimitations(
        EventSource.CLUB_OST,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the venue programmes through Resident Advisor and leaves the CMS description empty on every event"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the listing carries no category; every card is a flyer, a title, a start time and a ticket link"),
        AcceptedLimitation(LimitedAspect.GENRE, "the listing carries no genre"),
        AcceptedLimitation(LimitedAspect.PRICE, "the listing carries no price; tickets are sold on Resident Advisor"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the listing carries no lineup, though the CMS holds an empty div where one would go"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing carries one time per night and no doors time")
    )

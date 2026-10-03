package de.norm.events.scraper.so36

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for SO36 Berlin's Ticket-Toaster shop platform.
 *
 * 1. [HtmlFetcher] fetches `/tickets` conditionally (ETag / Last-Modified). The configured
 * source URL points straight at `/tickets` (the homepage 302-redirects there) to save the hop,
 * though the scraper client now follows redirects either way.
 * 2. [So36OverviewPageScraper] discovers every event and its detail URL (plus fallback title and date).
 * 3. Each `/produkte/…` detail page via [HtmlFetcher].
 * 4. [So36DetailPageScraper] — primary for type, subtitle, times, description, image, price,
 * free admission, ticket link, promoter and status.
 *
 * @see So36OverviewPageScraper for overview parsing (discovery, fallback data).
 * @see So36DetailPageScraper for detail parsing (the primary per-event source).
 * @see <a href="https://www.so36.com/tickets">SO36 program</a>
 */
@Component
class So36WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, So36OverviewPageScraper()::scrape, So36DetailPageScraper()::scrape) {
    /** Only the event page types a night, so a failed page keeps the stored type (#2505). */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.EVENT_TYPE)

    override val eventSource: EventSource = EventSource.SO36
    override val listsWholeProgramme: Boolean = true
}

val SO36_LIMITATIONS =
    VenueLimitations(
        EventSource.SO36,
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the shop exposes prices only as ticket categories, so a door-only event without an Abendkasse category carries no figure"
        ),
        AcceptedLimitation(
            LimitedAspect.SOLD_OUT,
            "the JSON-LD offer reports `SoldOut` for the external shops most events sell through, even when those shops still have tickets, so it is not read"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop tags each event only as Konzert, Party or Event, never a genre")
    )

package de.norm.events.scraper.astra

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Astra Kulturhaus' Kulturhäuser-platform event listing.
 *
 * 1. [HtmlFetcher] fetches the overview (the homepage `/`) conditionally (ETag / Last-Modified).
 * 2. [AstraOverviewPageScraper] parses the articles — primary for event type and sold-out
 * status, discovery + fallback for every other field.
 * 3. Each detail page via [HtmlFetcher].
 * 4. [AstraDetailPageScraper] — primary for promoter, prices, ticket URL and description.
 *
 * @see AstraOverviewPageScraper for overview page parsing (event type, discovery, fallback)
 * @see AstraDetailPageScraper for detail page parsing (promoter, prices, ticket, description)
 * @see <a href="https://www.astra-berlin.de/">Astra Kulturhaus</a>
 */
@Component
class AstraWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, AstraOverviewPageScraper()::scrape, AstraDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ASTRA
    override val listsWholeProgramme: Boolean = true

    /**
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative for promoter, prices, ticket URL and description; the overview for event type
     * and artists, and it supplies the date for the dateless featured teaser.
     *
     * The detail page *can* carry a `kind` label, but it is the raw per-day value without
     * [AstraOverviewPageScraper]'s festival-day normalization, so the overview type wins and the
     * detail value is only a fallback when the overview had no label.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            // Overview is authoritative for the type (it normalizes mislabeled festival days); the detail
            // kind only when the overview had no label.
            eventType = fallback.eventType ?: primary.eventType
        )
}

val ASTRA_LIMITATIONS =
    VenueLimitations(
        EventSource.ASTRA,
        AcceptedLimitation(LimitedAspect.GENRE, "the event page carries no genre field")
    )

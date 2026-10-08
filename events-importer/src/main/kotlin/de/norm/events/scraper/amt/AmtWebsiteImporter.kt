package de.norm.events.scraper.amt

import de.norm.events.scraper.AbstractMonthPagesWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for AMT Club Berlin — a Webflow techno club whose `/events` entry page
 * carries no events server-side (a Finsweet CMS-nest list injects them client-side). The entry
 * page links to per-month `/month/<name>` pages, each fully server-rendered; this importer
 * discovers those links, fetches each, and parses its events via [AmtOverviewPageScraper].
 *
 * Conditional requests are intentionally **not** used: the entry-page ETag changes only when a
 * month is added, not when a night is edited within one, so relying on it would miss mid-month
 * edits. Every run re-fetches entry and month pages and relies on idempotent `sourceId` upserts
 * — [ImportResult.Success] with `null` cache headers (no `NotModified` path). Past-dated nights
 * left on the current-month page are dropped centrally at persistence (`EventUpsertService`).
 *
 * The venue leaves gaps: from September 2026 it stopped filling its own months for a while and
 * announced its nights on Resident Advisor only (#1677), then posted October again (#2712). A
 * zero here is the venue first, the parser second — check the entry page's `/month/` links.
 *
 * @see AmtOverviewPageScraper for the per-month parsing.
 * @see <a href="https://www.club-amt.berlin/events">AMT events page</a>
 */
@Component
class AmtWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractMonthPagesWebsiteImporter(htmlFetcher, "AMT", MONTH_LINK_SELECTOR, AmtOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.AMT

    private companion object {
        /** The entry page's `/month/<name>` links, one per published month. */
        const val MONTH_LINK_SELECTOR = "a[href^=\"/month/\"]"
    }
}

val AMT_LIMITATIONS =
    VenueLimitations(
        EventSource.AMT,
        AcceptedLimitation(LimitedAspect.ARTISTS, "the DJ line separates names with spaces and nothing else, so it cannot be split apart reliably"),
        AcceptedLimitation(LimitedAspect.START_TIME, "the month and event pages print a date but no clock time"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the month and event pages print a date but no clock time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the month pages leave the price cell out, and the event pages print no figure"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the event page carries icons and one picture shared by every night, no poster of its own")
    )

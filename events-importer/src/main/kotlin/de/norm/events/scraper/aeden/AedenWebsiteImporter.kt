package de.norm.events.scraper.aeden

import de.norm.events.scraper.AbstractMonthPagesWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for ÆDEN Berlin — a WordPress techno club whose `/events` entry page holds
 * no events, only one `a.month-button` per upcoming month. Each links to a server-rendered
 * `/month/?month=YYYY-MM` page; this importer discovers those links, fetches each month, and
 * parses its nights via [AedenOverviewPageScraper].
 *
 * Conditional requests are intentionally **not** used: the entry page changes only when a
 * month is added or drops off, never when a night inside a month is edited, so its ETag would
 * mask mid-month edits. Every run re-fetches entry and month pages and relies on idempotent
 * `sourceId` upserts — [ImportResult.Success] with `null` cache headers (no `NotModified`
 * path). Passed nights still listed on the current month's page are dropped centrally at
 * persistence (`EventUpsertService`).
 *
 * Only the club programme is imported: the venue's other spaces live in the `aeve` / `oel`
 * post types, which never appear on the month pages.
 *
 * @see AedenOverviewPageScraper for the per-month parsing.
 * @see <a href="https://aedenberlin.com/events/">ÆDEN events page</a>
 */
@Component
class AedenWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractMonthPagesWebsiteImporter(htmlFetcher, "ÆDEN", MONTH_LINK_SELECTOR, AedenOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.AEDEN
    override val listsWholeProgramme: Boolean = true

    private companion object {
        /**
         * The entry page's month buttons. Their hrefs are absolute
         * (`https://aedenberlin.com/month/?month=2026-08`) in the rendered markup.
         */
        const val MONTH_LINK_SELECTOR = "a.month-button[href]"
    }
}

val AEDEN_LIMITATIONS =
    VenueLimitations(
        EventSource.AEDEN,
        AcceptedLimitation(LimitedAspect.PRICE, "the month page carries no prices"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the month page links no page per night")
    )

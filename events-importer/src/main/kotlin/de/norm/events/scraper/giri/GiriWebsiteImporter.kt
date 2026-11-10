package de.norm.events.scraper.giri

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Giri, a WordPress theme with its own programme template. `/programme/`
 * shows the current month and links the next one as `?month=YYYY-MM` from `#month-next`, so the
 * walk stops at the first later month that lists no event ([GiriProgrammePageScraper]). The entry
 * page never stops it: late in a month it can list nothing yet still link the next. `robots.txt`
 * bars the theme's JSON export (`/wp-json/giri/v1/events`), so the HTML is the source.
 *
 * Each event links its own page, which is the event's source URL. The calendar link's
 * `data-event` id is its identity.
 *
 * @see <a href="https://giri.berlin/programme/">Giri programme</a>
 */
@Component
class GiriWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Giri programme", GiriProgrammePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.GIRI
    override val maxListingPages: Int = MAX_MONTHS

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? {
        val isLaterMonth = "month=" in url
        if (isLaterMonth && document.select("a.calendar-event").isEmpty()) return null
        return document.selectFirst(NEXT_MONTH_SELECTOR)?.absUrl("href")?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** A runaway guard on the month walk; the venue publishes about two months ahead. */
        const val MAX_MONTHS = 12

        const val NEXT_MONTH_SELECTOR = "a#month-next[aria-label=Next month]"
    }
}

val GIRI_LIMITATIONS =
    VenueLimitations(
        EventSource.GIRI,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the line under the title is a list of styles, stored as the genre", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.START_TIME, "the site prints one doors time per night", verified = "2026-11-07"),
        AcceptedLimitation(LimitedAspect.END_TIME, "nights run open-ended, printed as `From 18:00-end`", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE, "entry is by donation, and a suggested figure appears only in some descriptions", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "a collaborating crew appears only in the title, as `GIRI x CURRENTS`", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "entry is by RSVP and donation, with no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear", verified = "2026-11-07")
    )

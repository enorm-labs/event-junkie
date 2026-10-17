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
 * shows the current month and links the next one as `?month=YYYY-MM`, without end, so the walk
 * stops at the first later month that lists nothing ([GiriProgrammePageScraper]). The entry page
 * never stops it: late in a month it can list nothing yet still link the next. `robots.txt` bars
 * the theme's JSON export (`/wp-json/giri/v1/events`), so the HTML is the source.
 *
 * The `?giri_programme_event=` link of each event answers 404, so an event points at its entry in the
 * month's list view, and the link's key is the event's identity.
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
        if (isLaterMonth && document.select("article.programme-detail").isEmpty()) return null
        return document.selectFirst(NEXT_MONTH_SELECTOR)?.absUrl("href")?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** A runaway guard on the month walk; the venue publishes about two months ahead. */
        const val MAX_MONTHS = 12

        const val NEXT_MONTH_SELECTOR = "nav.giri-month-nav a[aria-label=Next month]"
    }
}

val GIRI_LIMITATIONS =
    VenueLimitations(
        EventSource.GIRI,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the line under the title is a list of styles, stored as the genre"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "nights run open-ended, printed as `From 18:00-end`"),
        AcceptedLimitation(LimitedAspect.PRICE, "entry is by donation, and a suggested figure appears only in some descriptions"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "a collaborating crew appears only in the title, as `GIRI x CURRENTS`"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "entry is by RSVP and donation, with no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the event links answer 404, so every night points at the month's list view")
    )

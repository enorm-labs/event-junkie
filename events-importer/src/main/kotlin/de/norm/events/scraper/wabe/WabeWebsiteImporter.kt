package de.norm.events.scraper.wabe

import de.norm.events.scraper.AbstractMonthPagesWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Importer for WABE, a Jimdo site with one page per month (`/okt-2026/`). The navigation of every
 * page links the published months, so the home page is the entry. Each month page is read with
 * [WabeMonthPageScraper]. A row whose `Infos` button opens its own page (`/okt-2026/3/`) gets the
 * description and the ticket link from it ([WabeEventPageScraper]). Other rows link a guest
 * house's site or the `/open-mic/` series page, which name no single night, so they stay as listed.
 * `robots.txt` asks for a five-second crawl delay, which the per-host throttle keeps.
 */
@Component
class WabeWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractMonthPagesWebsiteImporter(htmlFetcher, "WABE", MONTH_LINK_SELECTOR, WabeMonthPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.WABE

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = WabeEventPageScraper()::scrape

    override fun hasEventPage(event: ScrapedEvent): Boolean = EVENT_PAGE.containsMatchIn(event.sourceUrl)

    private companion object {
        /** A month page's link, `/okt-2026/`, in the site navigation. */
        const val MONTH_LINK_SELECTOR = "a[href~=^(https://www\\.wabe-berlin\\.info)?/[a-z]{3}-\\d{4}/?$]"

        /** A night's own page, `/okt-2026/3/`, under its month page. */
        val EVENT_PAGE = Regex("""/[a-z]{3}-\d{4}/\d+/?$""")
    }
}

val WABE_LIMITATIONS =
    VenueLimitations(
        EventSource.WABE,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one time per night, the start")
    )

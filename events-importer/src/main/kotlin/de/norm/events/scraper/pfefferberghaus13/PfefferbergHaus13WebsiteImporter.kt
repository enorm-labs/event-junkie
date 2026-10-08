package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Pfefferberg Haus 13: the `/veranstaltungen/` listing and its `/page/N/` pages, then each event's page.
 *
 * The site also serves an Event Organiser iCal feed, but it holds every event since 2015 (1.2 MB), and the event pages
 * carry the price and the ticket link the feed lacks.
 *
 * @see PfefferbergHaus13OverviewPageScraper for the listing rows.
 * @see PfefferbergHaus13DetailPageScraper for the event page.
 */
@Component
class PfefferbergHaus13WebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher, PfefferbergHaus13OverviewPageScraper(clock)::scrape, PfefferbergHaus13DetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PFEFFERBERG_HAUS_13

    /** The listing's one time is the doors when the event page names doors and no start, so it is no start then. */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        val merged = primary.withGapsFrom(fallback)
        return if (primary.startTime == null && fallback.startTime == primary.doorsTime) merged.copy(startTime = null) else merged
    }

    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? {
        val current =
            PAGE
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 1
        return document.select("a[href*=/veranstaltungen/page/]").firstOrNull { it.text().trim() == "${current + 1}" }?.absUrl("href")
    }

    private companion object {
        val PAGE = Regex("""/page/(\d+)/""")
    }
}

val PFEFFERBERG_HAUS_13_LIMITATIONS =
    VenueLimitations(
        EventSource.PFEFFERBERG_HAUS_13,
        AcceptedLimitation(LimitedAspect.END_TIME, "the pages name doors and a start, no end"),
        AcceptedLimitation(LimitedAspect.GENRE, "no event names a style")
    )

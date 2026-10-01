package de.norm.events.scraper.ufafabrik

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
 * ufaFabrik's programme is a Drupal 10 calendar, one page per month. The source URL
 * `/spielplan.html` is the current month, and [nextOverviewPage] adds the next one from the
 * month buttons, so a run always sees at least four weeks ahead. Each show's page adds only the
 * blurb; the month row owns every other field, because one show page serves every date of a run
 * and prints only one of them.
 */
@Component
class UfaFabrikWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(
        htmlFetcher,
        { document, _ -> MONTH_PAGE_SCRAPER.scrape(document) },
        UfaFabrikEventPageScraper()::scrape
    ) {
    override val eventSource: EventSource = EventSource.UFA_FABRIK

    /** The month after the source page; a `/program/` URL is already that month, and ends the walk. */
    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? =
        if (MONTH_PAGE in url) {
            null
        } else {
            document.selectFirst("li:has(> a.button.active[href*=$MONTH_PAGE]) + li > a.button[href*=$MONTH_PAGE]")?.absUrl("href")?.ifEmpty { null }
        }

    /** The month row wins: the show page has only the blurb, which fills the row's gap. */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent = fallback.withGapsFrom(primary)

    private companion object {
        const val MONTH_PAGE = "/program/"
    }
}

val UFA_FABRIK_LIMITATIONS =
    VenueLimitations(
        EventSource.UFA_FABRIK,
        AcceptedLimitation(LimitedAspect.PAGINATION, "the calendar is one page per month, and only this month and the next are read"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the house publishes one time per show"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house names no promoter")
    )

private val MONTH_PAGE_SCRAPER = UfaFabrikMonthPageScraper()

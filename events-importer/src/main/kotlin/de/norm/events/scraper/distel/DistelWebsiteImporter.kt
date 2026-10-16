package de.norm.events.scraper.distel

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Kabarett-Theater DISTEL at Friedrichstraße, a Contao site.
 *
 * `/spielplan/kalender/` shows the current month; its navigation links the next as `?month=YYYYMM`,
 * and every month links a next one, so the walk stops at the first month with no performance:
 * about five months today. Each show page is then read once, for its text and photo, and applied
 * to every performance of the show; a failed show page flags its performances, so the upsert keeps
 * the text and photo stored last time.
 *
 * A calendar page weighs about 7 MB, almost all of it an animated SVG ticket icon inlined per
 * performance. The ticket shop has no listing to read instead, and its `robots.txt` disallows it.
 *
 * @see DistelCalendarPageScraper for the calendar parsing.
 * @see DistelShowPageScraper for the show page.
 * @see <a href="https://distel-berlin.de/spielplan/kalender/">DISTEL Kalender</a>
 */
@Component
class DistelWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "DISTEL", calendarScraper::scrape) {
    override val eventSource: EventSource = EventSource.DISTEL
    override val maxListingPages: Int = MAX_MONTHS
    override val sharedPages: SharedPages<DistelShow> = SharedPages(DistelShowPageScraper()::scrape, ::withShow, pageOwns = setOf(ScrapedField.IMAGE))

    /** The navigation's latest `?month=` link, or null once a month lists nothing. */
    override fun nextListingPage(
        document: Document,
        url: String
    ): String? =
        if (calendarScraper.isEmptyMonth(document)) {
            null
        } else {
            document
                .select("a[href*=kalender/?month=]")
                .maxByOrNull { it.attr("href").substringAfter("month=").take(MONTH_DIGITS) }
                ?.absUrl("href")
                ?.substringBefore('#')
        }

    private fun withShow(
        show: DistelShow,
        event: ScrapedEvent
    ): ScrapedEvent = event.copy(description = show.description, imageUrl = show.imageUrl)

    private companion object {
        /** A runaway guard; the calendar runs about five months ahead. */
        const val MAX_MONTHS = 12

        const val MONTH_DIGITS = 6

        val calendarScraper = DistelCalendarPageScraper()
    }
}

/**
 * German is the house language because the programme is German in practice (#2584). On `2026-10-04` the
 * production API listed 129 upcoming shows, and all 129 descriptions were German.
 */
val DISTEL_LIMITATIONS =
    VenueLimitations(
        EventSource.DISTEL,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar states one time per performance"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only inside the ticket shop, which robots.txt closes"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the ensemble's shows name a cast only in prose"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the calendar names no format for the ensemble's shows"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the theatre presents every performance itself"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site has one page per production, shared by all its performances"),
        houseLanguage = SpokenLanguage.GERMAN
    )

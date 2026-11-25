package de.norm.events.scraper.parkaue

import de.norm.events.scraper.AbstractMonthPagesWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Theater an der Parkaue, Berlin's state theatre for young audiences, a spiritec WebCMS site.
 * `/spielplan/` links one page per month, `/spielplan/spielplan/2027-01/`, about three months ahead; each month page
 * lists its dates. Each date links the production page with its own number, so the page is read once per production
 * (its slug) for the text and a photo.
 *
 * About half the dates are morning shows for schools. EVENT_SCOPE.md §3.5 marks an age under 10 `for-children`; a 10+
 * or 14+ play is an ordinary show (#5078).
 *
 * @see ParkaueMonthPageScraper for a month page.
 * @see ParkaueProductionPageScraper for a production page.
 * @see <a href="https://www.parkaue.de/spielplan/">Theater an der Parkaue Spielplan</a>
 */
@Component
class ParkaueWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractMonthPagesWebsiteImporter(htmlFetcher, "Theater an der Parkaue", "a[href]", ParkaueMonthPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PARKAUE

    override fun monthPageUrls(
        entry: Document,
        url: String
    ): List<String> =
        entry
            .select("a[href]")
            .map { it.absUrl("href") }
            .filter { MONTH_PAGE.containsMatchIn(it) }
            .distinct()

    override val sharedPages: SharedPages<ParkaueProduction> =
        SharedPages(ParkaueProductionPageScraper()::scrape, ::withProduction, key = { PRODUCTION.find(it.sourceUrl)?.groupValues?.get(1) ?: it.sourceUrl })

    private fun withProduction(
        production: ParkaueProduction,
        event: ScrapedEvent
    ): ScrapedEvent = event.copy(description = production.description, imageUrl = event.imageUrl ?: production.imageUrl)

    private companion object {
        val MONTH_PAGE = Regex("""/spielplan/spielplan/\d{4}-\d{2}/$""")
        val PRODUCTION = Regex("""/([^/]+)/\d+/?$""")
    }
}

val PARKAUE_LIMITATIONS =
    VenueLimitations(
        EventSource.PARKAUE,
        AcceptedLimitation(LimitedAspect.PRICE, "the site states no price; the ticket shop holds it", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes only the start time", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.END_TIME, "only some dates print an end (3 of 15 on the November page)", verified = "2026-11-25"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names formats and ages, not a music genre", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a play names its author and director, not its cast", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no presenter per date", verified = "2026-11-15")
    )

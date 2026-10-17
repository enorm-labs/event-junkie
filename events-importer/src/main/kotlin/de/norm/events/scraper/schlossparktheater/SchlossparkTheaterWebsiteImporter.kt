package de.norm.events.scraper.schlossparktheater

import de.norm.events.scraper.AbstractMonthPagesWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Schlosspark Theater in Steglitz, a hand-built PHP site. The calendar
 * (`/spielplan/kalender.html`) shows the current month and links every month of the season in
 * `#monatsnavi`, about a year ahead; each month page is read, about 240 performances in all. The
 * pages declare `<base href>` at the site root, which the month links resolve against.
 *
 * Each production page is then read once for its text and applied to every performance of the
 * production, about 70 pages a run. A failed page flags its performances, so the upsert keeps
 * the text stored last time. `robots.txt` sets no crawl delay.
 *
 * @see SchlossparkTheaterMonthPageScraper for the month page.
 * @see SchlossparkTheaterProductionPageScraper for the production page.
 * @see <a href="https://www.schlossparktheater.de/spielplan/kalender.html">Schlosspark Theater Spielplan</a>
 */
@Component
class SchlossparkTheaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractMonthPagesWebsiteImporter(
        htmlFetcher,
        "Schlosspark Theater",
        "#monatsnavi a[href\$=.html]",
        SchlossparkTheaterMonthPageScraper()::scrape
    ) {
    override val eventSource: EventSource = EventSource.SCHLOSSPARK_THEATER

    override val sharedPages: SharedPages<String> =
        SharedPages(SchlossparkTheaterProductionPageScraper()::scrape, { text, event -> event.copy(description = text) })
}

val SCHLOSSPARK_THEATER_LIMITATIONS =
    VenueLimitations(
        EventSource.SCHLOSSPARK_THEATER,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site states one start time per performance"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the site states one price range per performance, not a box-office price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a production or a themed concert names its cast only in prose or a subtitle line"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the site names no category, so the type comes from words in the billing, and a night without one is typed a show"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no presenter per performance"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site has one page per production, shared by all its performances")
    )

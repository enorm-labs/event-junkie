package de.norm.events.scraper.spindlerklatt

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Spindler & Klatt, a Wix site. The club page `/club` lists the whole
 * programme in a static repeater ([SpindlerKlattOverviewPageScraper]). Its `wix-warmup-data`
 * holds neither Wix Events nor a Wix Data collection, so the rendered HTML is read.
 *
 * The site has no page per night, and the club holds one night a date, so the date is the
 * night's identity. The `/en/club` page repeats the same German titles.
 *
 * @see <a href="https://www.spindlerklatt.com/club">Spindler & Klatt club programme</a>
 */
@Component
class SpindlerKlattWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Spindler & Klatt club page", SpindlerKlattOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SPINDLER_KLATT
    override val listsWholeProgramme: Boolean = true
}

val SPINDLER_KLATT_LIMITATIONS =
    VenueLimitations(
        EventSource.SPINDLER_KLATT,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line and a title, no second title line"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the club page prints no text about a night"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a themed party"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints an end time only for an early night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site publishes no line-ups"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the club page")
    )

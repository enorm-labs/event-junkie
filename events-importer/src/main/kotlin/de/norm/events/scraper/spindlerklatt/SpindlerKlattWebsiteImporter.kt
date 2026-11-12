package de.norm.events.scraper.spindlerklatt

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Spindler & Klatt. The club page `/club` lists the whole programme as
 * static cards in plain HTML ([SpindlerKlattOverviewPageScraper]). The flyers stay on Wix's
 * media host.
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
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line and a title, no second title line", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the club page prints no text about a night", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a themed party", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints an end time only for an early night", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site publishes no line-ups", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party's name", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-11-12"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear", verified = "2026-11-12"),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the site publishes no page per night, so every night points at the club page",
            verified = "2026-11-12"
        )
    )

package de.norm.events.scraper.beateuwe

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Beate Uwe, a WordPress site built in Elementor. The homepage carries the
 * whole programme, about four weeks, as hand-edited widgets ([BeateUweOverviewPageScraper]). The
 * REST API answers 403 and there is no event post type, feed or JSON-LD event, so one HTML fetch.
 *
 * Nights link only to Resident Advisor, which is never fetched; the venue's own page is the source.
 * Past nights stay on the list for a week or two before the site removes them.
 *
 * @see <a href="https://beate-uwe.de/#Programm">Beate Uwe programme</a>
 */
@Component
class BeateUweWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Beate Uwe homepage", BeateUweOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.BEATE_UWE
    override val listsWholeProgramme: Boolean = true
}

val BEATE_UWE_LIMITATIONS =
    VenueLimitations(
        EventSource.BEATE_UWE,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the list prints a title and a lineup per night, no text about it", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a DJ night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the list carries no pictures", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "a hosting collective is named only in the title", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the homepage", verified = "2026-10-17")
    )

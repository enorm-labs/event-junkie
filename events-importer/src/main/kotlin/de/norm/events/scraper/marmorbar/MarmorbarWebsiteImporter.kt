package de.norm.events.scraper.marmorbar

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Marmorbar, a Wix site whose event list carries the upcoming nights in its warmup JSON.
 *
 * The list holds the whole upcoming programme (`hasMore: false`), and each event's own page adds the full text and the
 * ticket's price without the Wix fee. The German and English pages carry the same event text, so no second language.
 *
 * @see MarmorbarOverviewPageScraper for the listing.
 * @see MarmorbarDetailPageScraper for the event page.
 */
@Component
class MarmorbarWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, MarmorbarOverviewPageScraper()::scrape, MarmorbarDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.MARMORBAR

    /** The listing has only the teaser and the price with the fee, so a failed page keeps the stored text and prices. */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.PRICES)
}

val MARMORBAR_LIMITATIONS =
    VenueLimitations(
        EventSource.MARMORBAR,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the bar publishes no categories; a night without a title cue is a DJ night"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "each night is billed by its name or its collective, and the DJs appear only in free prose"),
        AcceptedLimitation(LimitedAspect.GENRE, "the bar names no musical style except in free prose"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the collective that hosts a night is named only in its title or its prose"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar gives a start and an end time, no doors time"),
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the calendar gives each event a title and one text")
    )

package de.norm.events.scraper.speakeazy

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Speakeazy's Squarespace events page.
 *
 * `/events` lists every upcoming night ([SpeakeazyOverviewPageScraper]). Each event's own page adds the
 * blurb, the door price and the ticket link ([SpeakeazyEventPageScraper]). The collection's `?format=json`
 * is disallowed by `robots.txt`, so the HTML is the source.
 *
 * @see <a href="https://www.speakeazyberlin.de/events">Speakeazy programme</a>
 */
@Component
class SpeakeazyWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Speakeazy", SpeakeazyOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SPEAKEAZY
    override val listsWholeProgramme: Boolean = true
    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = SpeakeazyEventPageScraper()::enrich
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.PRICES)
}

val SPEAKEAZY_LIMITATIONS =
    VenueLimitations(
        EventSource.SPEAKEAZY,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the listing prints a start and an end time and no doors time"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the venue prints only the box-office price"),
        AcceptedLimitation(LimitedAspect.GENRE, "the listing names no style, only the blurb describes one"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )

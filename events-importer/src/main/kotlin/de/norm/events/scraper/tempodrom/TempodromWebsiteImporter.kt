package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Tempodrom's programme.
 *
 * `/programm-und-tickets/` embeds the whole programme as schema.org `Event` JSON-LD, so an
 * HTML fetch whose *payload* is structured data, parsed by [TempodromOverviewPageScraper]. The
 * JSON-LD names no organizer, and every event page credits one under "Veranstalter", so each
 * event's page is read for its promoter ([TempodromDetailPageScraper], #2711). That costs one GET
 * per event and stops the listing's `Last-Modified` 304 skipping a run: a promoter can change on a
 * page while the listing stays the same. A failed page flags the row, and the upsert keeps the stored promoter.
 *
 * @see TempodromOverviewPageScraper for the JSON-LD parsing logic.
 * @see TempodromDetailPageScraper for the promoter credit.
 * @see <a href="https://www.tempodrom.de/programm-und-tickets/">Tempodrom programme</a>
 */
@Component
class TempodromWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Tempodrom", { document, _ -> TempodromOverviewPageScraper().scrape(document) }) {
    override val eventSource: EventSource = EventSource.TEMPODROM
    override val listsWholeProgramme: Boolean = true

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = TempodromDetailPageScraper()::addPromoter
}

val TEMPODROM_LIMITATIONS =
    VenueLimitations(
        EventSource.TEMPODROM,
        AcceptedLimitation(LimitedAspect.PRICE, "a promoter-sold event has a JSON-LD offer with no price, and its page prints no Preis line"),
        AcceptedLimitation(LimitedAspect.GENRE, "the JSON-LD and the event pages carry no genre field")
    )

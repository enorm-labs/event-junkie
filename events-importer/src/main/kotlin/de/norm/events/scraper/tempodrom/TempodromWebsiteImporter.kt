package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Tempodrom's programme.
 *
 * `/programm-und-tickets/` embeds the whole programme as schema.org `Event` JSON-LD, so an
 * HTML fetch whose *payload* is structured data — the markup is never selected against, no
 * detail page needed. [HtmlFetcher] fetches the listing conditionally (the server sends
 * `Last-Modified`, so an unchanged programme costs one 304), [TempodromOverviewPageScraper]
 * parses the JSON-LD.
 *
 * @see TempodromOverviewPageScraper for the JSON-LD parsing logic.
 * @see <a href="https://www.tempodrom.de/programm-und-tickets/">Tempodrom programme</a>
 */
@Component
class TempodromWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Tempodrom", { document, _ -> TempodromOverviewPageScraper().scrape(document) }) {
    override val eventSource: EventSource = EventSource.TEMPODROM
    override val listsWholeProgramme: Boolean = true
}

val TEMPODROM_LIMITATIONS =
    VenueLimitations(
        EventSource.TEMPODROM,
        AcceptedLimitation(LimitedAspect.PRICE, "a promoter-sold event has a JSON-LD offer with no price, and its page prints no Preis line"),
        AcceptedLimitation(LimitedAspect.GENRE, "the JSON-LD and the event pages carry no genre field")
    )

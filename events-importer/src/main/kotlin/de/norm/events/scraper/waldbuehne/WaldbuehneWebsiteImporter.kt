package de.norm.events.scraper.waldbuehne

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
 * Website importer for the Waldbühne's programme.
 *
 * `/programm-und-tickets/` embeds the whole season as schema.org `MusicEvent` JSON-LD
 * ([WaldbuehneOverviewPageScraper]), about twenty dates. Each event's page adds the support act, the
 * clocks, the ticket shop, the promoter and the blurb ([WaldbuehneEventPageScraper]), so the run costs
 * one GET per date. A failed page flags the row, and the upsert keeps what that page stored last time.
 *
 * @see <a href="https://www.waldbuehne-berlin.de/programm-und-tickets/">Waldbühne programme</a>
 */
@Component
class WaldbuehneWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Waldbühne", WaldbuehneOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.WALDBUEHNE
    override val listsWholeProgramme: Boolean = true

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = WaldbuehneEventPageScraper()::enrich

    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.SUBTITLE, ScrapedField.DESCRIPTION, ScrapedField.ARTISTS)
}

val WALDBUEHNE_LIMITATIONS =
    VenueLimitations(
        EventSource.WALDBUEHNE,
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "a date months ahead prints `tbd` for doors and start until the promoter sets them",
            verified = "2026-11-02"
        ),
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "a date months ahead prints `tbd` for doors and start until the promoter sets them",
            verified = "2026-11-02"
        ),
        AcceptedLimitation(LimitedAspect.PRICE, "a date not yet on sale or sold out has a JSON-LD offer with no price", verified = "2026-11-02"),
        AcceptedLimitation(LimitedAspect.GENRE, "the JSON-LD and the event pages carry no genre field", verified = "2026-11-02")
    )

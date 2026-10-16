package de.norm.events.scraper.scheinbar

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Scheinbar Varieté in Schöneberg. `/programm/` lists about three months
 * on one page; each programme page is then read once, for its text and photo, and applied to every
 * evening that links it. A host's four Open Stage nights share one page, which is why this sets
 * [sharedPages] and not [enrichFromEventPage], which reads a page per evening. An evening whose page fails is flagged, so the upsert keeps what it stored.
 *
 * The stage plays one show a night, so the date is the evening's identity.
 *
 * @see ScheinbarProgrammPageScraper for the listing.
 * @see <a href="https://www.scheinbar.de/programm/">Scheinbar Varieté Programm</a>
 */
@Component
class ScheinbarWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Scheinbar Varieté", ScheinbarProgrammPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SCHEINBAR

    override val sharedPages: SharedPages<ScheinbarProgramme> =
        SharedPages(ScheinbarDetailPageScraper()::scrape, { programme, event ->
            event.copy(description = programme.description, imageUrl = programme.imageUrl)
        })
}

/**
 * German is the house language because the programme is German in practice (#2584). On `2026-10-04` the
 * production API listed 49 upcoming shows under 16 titles, and all 49 descriptions were German. No show
 * named another language.
 */
val SCHEINBAR_LIMITATIONS =
    VenueLimitations(
        EventSource.SCHEINBAR,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme states one time per evening"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "tickets are reserved online and paid at the box office, at one price"),
        AcceptedLimitation(LimitedAspect.GENRE, "the house names no genre; an Open Stage night mixes comedy, magic, music and artistry"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house presents every evening itself"),
        houseLanguage = SpokenLanguage.GERMAN
    )

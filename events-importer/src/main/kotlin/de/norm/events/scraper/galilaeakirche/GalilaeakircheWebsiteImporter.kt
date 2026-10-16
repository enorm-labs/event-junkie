package de.norm.events.scraper.galilaeakirche

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Galiläakirche, a WordPress site with the Very Simple Event List plugin. `/programm/` lists
 * every upcoming event on one page; each event's own page adds the text, the doors time, the prices and the ticket link.
 *
 * The REST type `wp/v2/event` exists but carries no date and no custom fields, so the HTML is the source.
 *
 * TranslatePress serves an English version of each page under `/en/`. It is a machine translation that renders names as
 * words (`Wolke Milena Wilke` becomes `Wolken`), so it is not the publisher's text and is not read (ADR-026).
 *
 * @see GalilaeakircheOverviewPageScraper for the listing.
 * @see GalilaeakircheEventPageScraper for the event pages.
 */
@Component
class GalilaeakircheWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, GalilaeakircheOverviewPageScraper()::scrape, GalilaeakircheEventPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.GALILAEAKIRCHE
    override val listsWholeProgramme: Boolean = true
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.PRICES)
}

val GALILAEAKIRCHE_LIMITATIONS =
    VenueLimitations(
        EventSource.GALILAEAKIRCHE,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the programme has a title per event and no second title line"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site files events only as concert, reading, film, theatre or other, and names no musical style"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site states a start and a doors time, not an end"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the band fields hold prose or a video, so the act comes from the title"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names an organiser only in the event text"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status")
    )

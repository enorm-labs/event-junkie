package de.norm.events.scraper.pastiche

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
 * Website importer for Pastiche, a Cargo site with no API: the programme page, then each event's own page.
 *
 * The programme page lists the current month in server-rendered HTML, without a year
 * ([PasticheProgrammePageScraper]). Each event's page adds the poster and the full text
 * ([PasticheEventPageScraper]). A row without a button keeps the programme page as its link and is not
 * read again. The page shows one month, so the stale cleanup stays at the scrape's last date.
 *
 * @see <a href="https://pasticheinternational.com/programme-1">Pastiche programme</a>
 */
@Component
class PasticheWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Pastiche programme", PasticheProgrammePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.PASTICHE
    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = PasticheEventPageScraper()::enrich
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.IMAGE)

    override fun hasEventPage(event: ScrapedEvent): Boolean = !PROGRAMME_PAGE.containsMatchIn(event.sourceUrl)

    private companion object {
        val PROGRAMME_PAGE = Regex("""/programme(?:-\d+)?/?$""")
    }
}

val PASTICHE_LIMITATIONS =
    VenueLimitations(
        EventSource.PASTICHE,
        AcceptedLimitation(LimitedAspect.PAGINATION, "the programme page shows the current month only"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names a format only on some nights, so a night without one is read as a concert"
        ),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site has no line-up field, so only a night billed as live or DJ names its act"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each event states its start and end, no doors time"),
        AcceptedLimitation(LimitedAspect.PRICE, "the price sits in a shop widget the page renders with JavaScript"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the ticket status sits in the same shop widget"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled event can only disappear"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )

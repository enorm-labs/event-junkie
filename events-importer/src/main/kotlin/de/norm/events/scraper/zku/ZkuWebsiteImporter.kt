package de.norm.events.scraper.zku

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
 * Website importer for ZK/U – Zentrum für Kunst und Urbanistik, a TYPO3 site with no feed. The
 * homepage calendar holds every dated entry from the start of the month on ([ZkuCalendarScraper]),
 * and each entry's `/timeline/<slug>/` page adds the blurb, line-up, host and entry line
 * ([ZkuEventPageScraper]).
 *
 * The programme is mostly not a stage: markets, workshops, open studios and conferences share the
 * calendar with the talks, screenings, concerts and club nights, so the calendar scraper drops
 * them by name. The `/de/` pages are no second language: many fall back to the English text or
 * carry a `[Translate to Deutsch:]` placeholder.
 *
 * @see <a href="https://www.zku-berlin.org/">ZK/U homepage calendar</a>
 */
@Component
class ZkuWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the ZK/U calendar", ZkuCalendarScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ZKU

    private val eventPageScraper = ZkuEventPageScraper()

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = eventPageScraper::enrich

    /** The page has the full flyer and the line-up. The calendar has a crop and no acts. */
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.ARTISTS)
}

val ZKU_LIMITATIONS =
    VenueLimitations(
        EventSource.ZKU,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category, so the type comes from the entry's own words and an untyped one stays other"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one time range per entry"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled entry can only disappear"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a concert page bills its acts in free prose blocks, so only a `Line up` list or the title names them"),
        AcceptedLimitation(LimitedAspect.PRICE, "most pages print no entry line, and a ticket button links an outside shop without a price")
    )

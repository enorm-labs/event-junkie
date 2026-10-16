package de.norm.events.scraper.monsterronsons

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.nextPageUrl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Monster Ronson's Ichiban Karaoke, the Friedrichshain karaoke bar, on Webflow.
 *
 * Overview → night page:
 * 1. [HtmlFetcher] fetches `/events` conditionally (ETag / Last-Modified). The listing is paged
 * twelve nights at a time, about five weeks over four pages (#1884); the later pages are read to
 * the last, bounded by [MAX_PAGES]. Last-Modified is Webflow's site publish time, so a `304` on
 * the first page means no page changed.
 * 2. [MonsterRonsonsOverviewPageScraper] parses one event per calendar day — title, date, start
 * time, poster and host(s).
 * 3. Each night's `/posts/<slug>` page once, applying prose, door price and ticket link
 * ([MonsterRonsonsNightDetail.applyTo]).
 *
 * Reads the night pages as [sharedPages] rather than through
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] because the night page enriches the
 * card instead of superseding it: the base class treats the detail page as primary and merges
 * the overview into its gaps, the wrong way round here — the card is the only place the date is
 * stated in full, and a failed night page must not blank it. Such a page is not fatal: the date
 * keeps its card data and is flagged, so the upsert keeps the description, price and ticket link
 * stored last time. The CMS recycles entries, so two cards may link one page, which is read once.
 *
 * **Not published**: no doors time (one time per night, taken as the start), no presale price,
 * no genre, no lineup beyond the host, rarely a ticket link. The private karaoke boxes running
 * all evening appear nowhere — the listing is the main stage only.
 *
 * @see MonsterRonsonsOverviewPageScraper for the listing parsing logic.
 * @see MonsterRonsonsDetailPageScraper for the night-page parsing logic.
 * @see <a href="https://www.karaokemonster.de/events">Monster Ronson's events page</a>
 */
@Component
class MonsterRonsonsWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's year inference and past-event cutoff; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Monster Ronson's", MonsterRonsonsOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.MONSTER_RONSONS
    override val maxListingPages: Int = MAX_PAGES

    private val detailPageScraper = MonsterRonsonsDetailPageScraper()

    override val sharedPages: SharedPages<MonsterRonsonsNightDetail> =
        SharedPages({ detailPageScraper.scrape(it, it.location()) }, MonsterRonsonsNightDetail::applyTo)

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = document.nextPageUrl(NEXT_PAGE_SELECTOR)

    private companion object {
        /** A runaway guard on the page walk; the listing runs to four pages. */
        const val MAX_PAGES = 8

        /** Webflow's pagination link; the query parameter name is the collection list's id. */
        const val NEXT_PAGE_SELECTOR = "a.w-pagination-next[href]"
    }
}

val MONSTER_RONSONS_LIMITATIONS =
    VenueLimitations(
        EventSource.MONSTER_RONSONS,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue states one time per night, which is taken as the start"),
        AcceptedLimitation(LimitedAspect.PRICE, "the price lives in prose and is often a time-banded tariff, which the model has no field for"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre; every night takes the house's Karaoke"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the venue bills no lineup beyond the host named in the title"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "entry is paid at the door; a night rarely links a ticket shop"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the BOXHOPPING poster is a GIF whose black first frame is rejected as blank (#2669)"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself"),
        houseGenre = "Karaoke"
    )

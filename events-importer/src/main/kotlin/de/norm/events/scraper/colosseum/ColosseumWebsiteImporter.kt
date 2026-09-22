package de.norm.events.scraper.colosseum

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Colosseum's Wix Events programme.
 *
 * The whole programme — prices, sold-out flags and external ticket shops included — is in the
 * `wix-warmup-data` payload of the single `/event` page, so one HTTP request per cycle:
 * [HtmlFetcher] fetches `/event` conditionally (Wix serves a weak `ETag`),
 * [ColosseumOverviewPageScraper] parses the embedded JSON.
 *
 * **The `/details-registrierung/<slug>` pages are fetched for their times alone** (#1684). The
 * listing carries one `startDate` per event, and that time is the doors as often as it is the
 * start — 8 of 18 live events against 7 — so a row built from the listing alone puts a door time
 * in `startTime` about half the time. [ColosseumDetailPageScraper] reads the event's own `Einlass`
 * and `Beginn` lines; the listing's record is kept for everything else. Two fields stay refused:
 * - Their `about` is *not* per-event text. Each event is created by cloning an old one and that
 * section is never rewritten, so the same 3,440-character block — a Dustin O'Halloran biography
 * opening "Einlass: 19 Uhr / Beginn: 20 Uhr" — is served for a Cornelia Funke reading, an Irvine
 * Welsh evening and a football talk alike. It would attach a stranger's biography to nearly every
 * event, and it is the second Einlass line the time parsing steps over.
 * - Their `tickets[].price` is the face value, where the overview's `lowestTicketPrice` is the
 * checkout total including Wix's service fee. The total is what a buyer pays.
 *
 * The widget ships the upcoming window only (18 events) and reports `hasMore: true`; the rest
 * needs the authenticated widget API, as at MAXXIM. First page only is the standing decision
 * (ADR-007 §"Pagination — First Page Only"), and the events beyond it are the far-future tail —
 * the window already runs about six weeks out.
 *
 * @see ColosseumOverviewPageScraper for the parsing logic.
 * @see <a href="https://www.colosseumberlin.com/event">Colosseum programme</a>
 */
@Component
class ColosseumWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    override val eventSource: EventSource = EventSource.COLOSSEUM

    private val overviewPageScraper = ColosseumOverviewPageScraper()
    private val detailPageScraper = ColosseumDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url)

    /**
     * Keeps the listing's record whole and takes the two times from the event's own page.
     *
     * The listing is the richer source — prices, the sold-out flag, the poster, the ticket shop and
     * the type are all read from it — and the detail page is fetched for nothing else. A page that
     * states no clock leaves the listing's time where it was.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        fallback.copy(
            doorsTime = primary.doorsTime ?: fallback.doorsTime,
            startTime = primary.startTime ?: fallback.startTime
        )
}

val COLOSSEUM_LIMITATIONS =
    VenueLimitations(
        EventSource.COLOSSEUM,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "`categories` is empty on every event, so the type is inferred from the title and subtitle"),
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "an event whose own page states no Einlass line keeps the listing's single time as the start, and gets no doors"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the house names no musical style anywhere"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "no support-act convention exists in the subtitles, and a title is as often an event name as a performer's")
    )

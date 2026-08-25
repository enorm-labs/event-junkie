package de.norm.events.scraper.colosseum

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Colosseum's Wix Events programme.
 *
 * The whole programme — including prices, sold-out flags and external ticket shops — is carried by
 * the `wix-warmup-data` payload of the single `/event` page, so the pipeline is one HTTP request
 * per import cycle:
 * 1. Fetch `/event` via [HtmlFetcher] with conditional request support (Wix serves a weak `ETag`).
 * 2. Parse every event out of the embedded JSON via [ColosseumOverviewPageScraper].
 *
 * **The `/details-registrierung/<slug>` pages are deliberately not fetched.** They add exactly two
 * things, and neither survives inspection:
 * - Their `about` field is *not* per-event text. The house creates each event by cloning an old one
 *   and never rewrites that section, so the same 3,440-character block — a Dustin O'Halloran
 *   biography, opening "Einlass: 19 Uhr / Beginn: 20 Uhr" — is served verbatim for a Cornelia Funke
 *   reading, an Irvine Welsh evening and a football talk alike. Importing it would attach a
 *   stranger's biography, and a door time contradicting the event's own `startDate`, to nearly
 *   every event. Both the description and the doors time are therefore left empty.
 * - Their `tickets[].price` is the ticket's face value, where the overview's `lowestTicketPrice` is
 *   the checkout total including Wix's service fee. The total is what a buyer pays, so the overview
 *   figure is the better of the two — and the one that needs no extra request.
 *
 * The widget ships the upcoming window only (18 events) and reports `hasMore: true`; loading the
 * rest needs the authenticated widget API, as at MAXXIM. First page only is the standing decision
 * (ADR-007 §"Pagination — First Page Only"), and the events beyond it are the far-future tail —
 * the window already runs about six weeks out.
 *
 * @see ColosseumOverviewPageScraper for the parsing logic.
 * @see <a href="https://www.colosseumberlin.com/event">Colosseum programme</a>
 */
@Component
class ColosseumWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.COLOSSEUM

    private val overviewPageScraper = ColosseumOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from Colosseum" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val COLOSSEUM_LIMITATIONS =
    VenueLimitations(
        EventSource.COLOSSEUM,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "`categories` is empty on every event, so the type is inferred from the title and subtitle"),
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "the Wix payload carries one `startDate` per event, and the detail page repeats one boilerplate Einlass line for all of them"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the house names no musical style anywhere"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "no support-act convention exists in the subtitles, and a title is as often an event name as a performer's")
    )

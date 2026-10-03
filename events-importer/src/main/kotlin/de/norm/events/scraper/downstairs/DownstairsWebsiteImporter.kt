package de.norm.events.scraper.downstairs

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ListingPage
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.enrichFromSharedPages
import de.norm.events.scraper.walkListingPages
import de.norm.events.scraper.withQueryParameter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for the Downstairs Comedy Club in Mitte, Felix Lobrecht's club, from its
 * tickettoaster shop.
 *
 * The shop's `/tickets` page is an empty shell: the list arrives in the `tickets` Turbo frame,
 * which the shop renders only for a request carrying the `Turbo-Frame` header
 * ([HtmlFetcher.fetchTurboFrame]). The configured URL is that frame's source with `limit=100`;
 * a page that offers more names the next `page` in its form, so the walk reads two pages today.
 * The frame sends no validators, so every run reads it.
 *
 * Each show's first ticket page is then read once, for the text, image and price that its other
 * performances share; a failed page leaves them with the list's fields, flagged so the upsert
 * keeps what it stored.
 *
 * @see DownstairsTicketListScraper for the list.
 * @see <a href="https://www.downstairscomedy.shop/tickets">Downstairs tickets</a>
 */
@Component
class DownstairsWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.DOWNSTAIRS
    override val fetchesBeyondEntryPage: Boolean = true

    private val listScraper = DownstairsTicketListScraper()
    private val ticketScraper = DownstairsTicketPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val fetch: suspend (String) -> Document = { htmlFetcher.fetchTurboFrame(it, FRAME) }
        val walked =
            walkListingPages(eventSource, fetch(url), url, MAX_PAGES, fetch) { document, pageUrl ->
                ListingPage(listScraper.scrape(document, pageUrl), listScraper.nextPage(document)?.let { url.withQueryParameter("page", it) })
            }
        val events = enrichFromTicketPages(walked.items.distinctBy { it.sourceId })
        logger.info { "Scraped ${events.size} Downstairs performance(s) across ${walked.pages} page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = walked.complete)
    }

    private suspend fun enrichFromTicketPages(events: List<ScrapedEvent>): List<ScrapedEvent> =
        htmlFetcher.enrichFromSharedPages(events, ticketScraper::scrape, { show, event ->
            event.copy(description = show.description, imageUrl = show.imageUrl, pricePresale = show.price)
        }, key = ScrapedEvent::title)

    private companion object {
        const val FRAME = "tickets"

        /** A runaway guard on the page walk; the list runs to two pages. */
        const val MAX_PAGES = 10
    }
}

val DOWNSTAIRS_LIMITATIONS =
    VenueLimitations(
        EventSource.DOWNSTAIRS,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the shop states one time per performance"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the shop gives every performance the same 6 a.m. end"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the club's own nights bill a format, and the comedians appear only in prose"),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the club presents every show itself")
    )

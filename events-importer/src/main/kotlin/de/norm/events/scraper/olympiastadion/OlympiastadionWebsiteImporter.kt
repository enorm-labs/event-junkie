package de.norm.events.scraper.olympiastadion

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ListingPages
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.importEntryPage
import de.norm.events.scraper.readEventPage
import de.norm.events.scraper.readSecondLanguagePage
import de.norm.events.scraper.withDescriptionAlt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for the Olympiastadion Berlin, a WordPress site.
 *
 * `/termine-und-tickets/` lists the stadium's own events ([OlympiastadionOverviewPageScraper]); each
 * event page adds the start and doors times, the blurb and the ticket shop ([OlympiastadionDetailPageScraper]).
 * The English version of a kept event's page gives its blurb as the second language, one more request each.
 * The REST collection `/wp-json/wp/v2/events` carries neither a date nor a text, so the HTML is the source.
 *
 * **The yield is low by design.** Most of the stadium's dates are sport: Hertha's home games, the national
 * team and the runs that finish on the track. EVENT_SCOPE.md §3.1 keeps sport out, and only the event page
 * says which kind a card is, so a sport page drops its card. A typical year leaves one to three stadium
 * concerts. A failed page keeps its card, flagged so the upsert keeps the stored fields; a sport card whose
 * page fails is in for that run and gone at the next.
 *
 * @see <a href="https://olympiastadion.berlin/termine-und-tickets/">Olympiastadion events</a>
 */
@Component
class OlympiastadionWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.OLYMPIASTADION

    override val fetchesBeyondEntryPage: Boolean = true

    private val overviewPageScraper = OlympiastadionOverviewPageScraper()
    private val detailPageScraper = OlympiastadionDetailPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        htmlFetcher.importEntryPage(url, etag, lastModified) { document ->
            val cards = overviewPageScraper.scrape(document, url)
            val events = cards.mapNotNull { withDetail(it) }
            logger.info { "Scraped ${events.size} Olympiastadion event(s), skipping ${cards.size - events.size} sport event(s)" }
            ListingPages(events, complete = true)
        }

    /** The card with its page's fields, flagged when the page fails, or null when the page is a sport event's. */
    private suspend fun withDetail(card: ScrapedEvent): ScrapedEvent? {
        val detail = readEventPage(htmlFetcher::fetchDocument, card) { detailPageScraper.scrape(it) } ?: return card.copy(detailUnavailable = true)
        if (detail.sport) {
            logger.info { "Dropping '${card.title}': its page files it as sport" }
            return null
        }
        val event =
            card.copy(
                description = detail.description,
                startTime = detail.startTime,
                doorsTime = detail.doorsTime,
                ticketUrl = detail.ticketUrl
            )
        val english = detail.englishUrl?.takeIf { detail.description != null }
        return event.withDescriptionAlt(english?.let { readSecondLanguagePage(htmlFetcher::fetchDocument, it, detailPageScraper::description) })
    }
}

val OLYMPIASTADION_LIMITATIONS =
    VenueLimitations(
        EventSource.OLYMPIASTADION,
        AcceptedLimitation(LimitedAspect.GENRE, "the stadium names no style for a concert"),
        AcceptedLimitation(LimitedAspect.PRICE, "tickets are sold only through the promoter's outside shop, so the page prints no price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the page names the ticket shop, not the promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the page states no ticket status"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page gives no end time")
    )

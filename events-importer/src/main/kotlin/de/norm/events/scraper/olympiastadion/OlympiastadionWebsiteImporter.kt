package de.norm.events.scraper.olympiastadion

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventPageReader
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.VenueLimitations
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
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Olympiastadion", OlympiastadionOverviewPageScraper()::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.OLYMPIASTADION

    private val detailPageScraper = OlympiastadionDetailPageScraper()

    override val listedLine: ((Int) -> String)? = null

    override val doneLine: (Int, Int) -> String = { kept, cards -> "Scraped $kept Olympiastadion event(s), skipping ${cards - kept} sport event(s)" }

    override val eventPageReader = EventPageReader({ _, document -> detailPageScraper.scrape(document) }, ::withDetail)

    override val eventPageSecondLanguage = SecondLanguagePage(ENGLISH, detailPageScraper::description)

    /** The card with its page's fields, or null when the page is a sport event's. */
    private fun withDetail(
        card: ScrapedEvent,
        detail: OlympiastadionDetail
    ): ScrapedEvent? {
        if (detail.sport) {
            logger.info { "Dropping '${card.title}': its page files it as sport" }
            return null
        }
        return card.copy(
            description = detail.description,
            startTime = detail.startTime,
            doorsTime = detail.doorsTime,
            ticketUrl = detail.ticketUrl
        )
    }

    private companion object {
        const val ENGLISH = "en"
    }
}

val OLYMPIASTADION_LIMITATIONS =
    VenueLimitations(
        EventSource.OLYMPIASTADION,
        AcceptedLimitation(LimitedAspect.GENRE, "the stadium names no style for a concert", verified = "2026-11-02"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "tickets are sold only through the promoter's outside shop, so the page prints no price",
            verified = "2026-11-02"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the page names the ticket shop, not the promoter", verified = "2026-11-02"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the page states no ticket status", verified = "2026-11-02"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the page gives no end time", verified = "2026-11-02")
    )

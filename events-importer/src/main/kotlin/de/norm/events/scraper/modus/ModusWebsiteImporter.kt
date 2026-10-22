package de.norm.events.scraper.modus

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * Website importer for Modus Berlin's programme.
 *
 * 1. [HtmlFetcher] fetches the unpaginated `/events` page conditionally (ETag / Last-Modified).
 * 2. [ModusOverviewPageScraper] discovers every `.event-item` tile — title, rendered date and poster.
 * 3. Each `/event/DDMMYY-<Name>` page via [ModusDetailPageScraper] — start time, ticket link
 * and description.
 * 4. Each ticket link into the venue's own shop via [ModusTicketShopScraper] — the sold-out state,
 * which no Modus page shows. A shop page that fails leaves its night as the event page has it.
 *
 * @see ModusOverviewPageScraper for listing parsing (discovery, date, poster).
 * @see ModusDetailPageScraper for detail parsing (times, ticket, description).
 * @see <a href="https://modus-berlin.de/events">Modus event listing</a>
 */
@Component
class ModusWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, ModusOverviewPageScraper()::scrape, ModusDetailPageScraper()::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MODUS
    override val listsWholeProgramme: Boolean = true

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val result = super.importEvents(url, etag, lastModified)) {
            is ImportResult.Success -> result.copy(events = result.events.map { withShopSoldOut(it) })
            else -> result
        }

    @Suppress("TooGenericExceptionCaught") // A failed shop page must not cost the night; it keeps the event page's state
    private suspend fun withShopSoldOut(event: ScrapedEvent): ScrapedEvent {
        val ticketUrl = event.ticketUrl?.takeIf { isShopLink(it) } ?: return event
        return try {
            event.copy(soldOut = event.soldOut || ModusTicketShopScraper.soldOut(htmlFetcher.fetchDocument(ticketUrl)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the Modus ticket shop for '${event.title}', keeping the event page's sold-out state" }
            event
        }
    }

    private fun isShopLink(url: String): Boolean = runCatching { URI(url).host }.getOrNull() == ModusTicketShopScraper.TICKET_SHOP_HOST
}

val MODUS_LIMITATIONS =
    VenueLimitations(
        EventSource.MODUS,
        AcceptedLimitation(LimitedAspect.PRICE, "the event page prints no figure and sends buyers to the ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre field; the style is only in the text"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself")
    )

package de.norm.events.scraper.gretchen

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Website importer for Gretchen Berlin's hand-coded listing: all upcoming events on one
 * homepage (`/`) as `.gig` blocks with full details inline, so, like Privatclub and Frannz, one
 * conditional fetch via [HtmlFetcher] and one parse via [GretchenOverviewPageScraper].
 *
 * The ticket shop is not on the page: the `TICKETS` button opens a popup that a form POST to
 * [TICKET_POPUP_PATH] fills. One POST per night reads it ([parseTicketPopup]); the popup's
 * `list_id` is the night's `detail.php` id. A night whose popup fails or names no shop keeps the
 * Resident Advisor link the card carries.
 *
 * @see GretchenOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.gretchen-club.de/">Gretchen Berlin</a>
 */
@Component
class GretchenWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.GRETCHEN
    override val listsWholeProgramme: Boolean = true

    private val overviewPageScraper = GretchenOverviewPageScraper()

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
                val popupUrl = URI(url).resolve(TICKET_POPUP_PATH).toString()
                val events = overviewPageScraper.scrape(fetchResult.document, url).map { withTicketShop(it, popupUrl) }
                logger.info { "Scraped ${events.size} event(s) from Gretchen, ${events.count { it.ticketUrl != null }} with a ticket link" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /** [event] with the shop its `TICKETS` popup names, or unchanged when the popup names none or fails. */
    @Suppress("TooGenericExceptionCaught") // Intentional: one failed popup keeps the card's link, not the whole import
    private suspend fun withTicketShop(
        event: ScrapedEvent,
        popupUrl: String
    ): ScrapedEvent {
        val listId = event.sourceId.removePrefix(EventSource.GRETCHEN.sourceIdPrefix)
        return try {
            val shop = parseTicketPopup(htmlFetcher.postForm(popupUrl, mapOf("list_id" to listId, "pfad" to "./", "lang" to "de")))
            if (shop != null) event.copy(ticketUrl = shop) else event
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.at(Level.WARN) {
                message = "Gretchen ticket popup for '${event.title}' failed, keeping the card's link"
                cause = e
                payload = mapOf(LogFields.URL to popupUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
            }
            event
        }
    }

    private companion object {
        /** The endpoint the `TICKETS` button's `get_vvk_popup(<id>)` posts to. */
        const val TICKET_POPUP_PATH = "/funk/get_popup_vvk.php"
    }
}

val GRETCHEN_LIMITATIONS =
    VenueLimitations(
        EventSource.GRETCHEN,
        AcceptedLimitation(
            LimitedAspect.DESCRIPTION,
            "the text is only on each night's detail page; the import reads the homepage and the ticket popups, not those pages"
        ),
        AcceptedLimitation(LimitedAspect.PRICE, "club nights print no price, and a cancelled show loses its line; presale concerts list theirs")
    )

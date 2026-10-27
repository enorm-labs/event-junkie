package de.norm.events.scraper.marmorbar

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WIX_REGISTRATION_OPEN_TICKETS
import de.norm.events.scraper.WIX_REGISTRATION_TICKETS
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.mapWixEventStatus
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Parser for the Marmorbar event list (`/en/event-list`), a Wix Events widget read from its warmup JSON.
 *
 * The listing gives the date, the times, the poster, a teaser and the sold-out flag. Its price is the checkout total with
 * Wix's service fee on top (€8.20 for an €8 ticket), so it is not read: [MarmorbarDetailPageScraper] takes the ticket's
 * own price. Every night is billed by its name or its collective, never by its act, so a title cue types it and
 * anything else is a DJ night. The event page path is relative, so it keeps the listing's language.
 */
class MarmorbarOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = WixEventsWarmupData.events(document, EventSource.MARMORBAR) ?: return emptyList()
        logger.info { "Found ${events.size()} event(s) in the Marmorbar Wix warmup payload" }
        return events.mapSkippingFailures(logger, "Marmorbar event") { parseEvent(it, baseUrl) }
    }

    private fun parseEvent(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent {
        val id = requireNotNull(node.stringOrNull("id")) { "Marmorbar event has no id" }
        val slug = requireNotNull(node.stringOrNull("slug")) { "Marmorbar event '$id' has no slug" }
        val cleanedTitle = node.stringOrNull("title")?.let(::cleanEventTitle)
        val title = requireNotNull(cleanedTitle?.text?.blankToNull()) { "Marmorbar event '$slug' has no title" }
        val schedule = parseWixSchedule(node.path("scheduling").path("config"))
        val date = requireNotNull(schedule.date) { "Marmorbar event '$slug' has no start date" }
        val registration = node.path("registration")
        val soldByWix = registration.path("type").asInt(0) == WIX_REGISTRATION_TICKETS
        val eventPage = resolveUrl(baseUrl, "event-details/$slug")
        val cue = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name }
        return ScrapedEvent(
            title = title,
            description = node.stringOrNull("description"),
            eventType = cue ?: EventType.PARTY.name,
            typeIsFallback = cue == null,
            eventDate = date,
            startTime = schedule.startTime,
            endDate = schedule.endDate,
            endTime = schedule.endTime,
            imageUrl = node.path("mainImage").stringOrNull("url"),
            sourceUrl = eventPage,
            // The slug is minted from the first title and a renamed night may get a new one; the id never changes.
            sourceId = "${EventSource.MARMORBAR.sourceIdPrefix}$id",
            ticketUrl =
                registration.path("external").stringOrNull("registration")
                    ?: eventPage.takeIf { soldByWix && registration.path("status").asInt(0) == WIX_REGISTRATION_OPEN_TICKETS },
            soldOut = (soldByWix && registration.path("ticketing").path("soldOut").asBoolean(false)) || cleanedTitle.soldOut,
            status = mapWixEventStatus(node.path("status"))
        )
    }
}

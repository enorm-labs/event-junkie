package de.norm.events.scraper.comedycafe

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Pure JSON parser for Comedy Café Berlin's **The Events Calendar** REST API; the page shape and
 * the field readers are [parseTecPage]'s.
 *
 * - **Everything is comedy**, mostly long-form improv, with stand-up, sketch and storytelling nights.
 * - **A show at the club's training studio is left out.** It runs at another address (CCB Studios,
 * Hasenheide 12), so only events whose `venue.slug` is the club's own are kept.
 * - **Doors open 15 minutes before the show**, a rule every event page states, so doors are derived.
 * - **`cost` is the online price**; `Free` comes as `0.00` in `cost_details`. A 23:59 end is the
 * plugin's placeholder for a late show with no stated end, and is dropped.
 */
class ComedyCafeApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.COMEDY_CAFE, ::toScrapedEvent)

    @Suppress("ReturnCount") // Guard clauses for the required fields and the venue are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "Comedy Café Berlin event '$slug' has no title, skipping" }
            return null
        }
        if (event.path("venue").stringOrNull("slug") != CLUB_VENUE_SLUG) {
            logger.debug { "Skipping Comedy Café Berlin event '$title': it runs at another venue" }
            return null
        }
        val end = event.tecDateTime("end_date")?.takeUnless { it.toLocalTime() == PLACEHOLDER_END || !it.isAfter(start) }
        val price =
            event
                .path("cost_details")
                .path("values")
                .firstOrNull()
                ?.asString("")
                ?.toBigDecimalOrNull()

        return ScrapedEvent(
            title = title,
            description = event.tecDescription(),
            eventType = EventType.COMEDY.name,
            eventDate = start.toLocalDate(),
            doorsTime = start.toLocalTime().minusMinutes(DOORS_BEFORE_SHOW_MINUTES),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = "${EventSource.COMEDY_CAFE.sourceIdPrefix}$slug",
            pricePresale = price?.takeIf { it.signum() > 0 },
            free = price?.compareTo(BigDecimal.ZERO) == 0,
            soldOut = hasSoldOutMarker(title)
        )
    }

    private companion object {
        const val CLUB_VENUE_SLUG = "comedy-cafe-berlin"
        const val DOORS_BEFORE_SHOW_MINUTES = 15L
        val PLACEHOLDER_END: LocalTime = LocalTime.of(23, 59)
    }
}

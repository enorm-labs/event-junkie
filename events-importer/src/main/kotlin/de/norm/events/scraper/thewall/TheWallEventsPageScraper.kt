package de.norm.events.scraper.thewall

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.net.URI

/**
 * Pure parser for one page of The Wall Comedy Club's programme on Spotagig, the ticketing platform
 * that serves the club's site. Every event on a page has a schema.org `Event` JSON-LD block, which
 * is all this reads.
 *
 * - The `startDate` carries `+00:00`, but its clock is Berlin time: the 20:00 show reads
 * `T20:00:00+00:00`. The offset is ignored, as `schemaTime` does.
 * - Each producer enters their own time: Anna Beros' Sunday show states its 17:30 doors, the
 * showcases their start. The one clock is stored as the start.
 * - `url` points at spotagig.eu; the club's own domain serves the same slug under `/events/`.
 * - The club's own productions name it as organizer, and only a guest producer is kept as promoter.
 */
class TheWallEventsPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val events = document.jsonLdEvents()
        logger.info { "Found ${events.size} The Wall JSON-LD event(s) on $baseUrl" }

        return events.mapSkippingFailures(logger, "The Wall event") { event ->
            toScrapedEvent(event)
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val title = event.schemaName()?.let(::cleanEventTitle) ?: return null
        val eventDate = event.schemaDate("startDate") ?: return null
        val slug =
            event
                .stringOrNull("url")
                ?.let { URI(it).path.trimEnd('/').substringAfterLast('/') }
                ?.takeIf { it.isNotBlank() } ?: return null
        val endDate = event.schemaDate("endDate")
        return ScrapedEvent(
            title = title,
            description = event.stringOrNull("description"),
            eventType = EventType.COMEDY.name,
            eventDate = eventDate,
            startTime = event.schemaTime("startDate"),
            endDate = endDate,
            endTime = endDate?.let { event.schemaTime("endDate") },
            imageUrl = event.schemaImageUrl(),
            sourceUrl = "$SITE/events/$slug/",
            sourceId = "${EventSource.THE_WALL.sourceIdPrefix}$slug",
            status = event.schemaStatus(),
            promoters = listOfNotNull(event.path("organizer").stringOrNull("name")?.takeUnless { it.startsWith(HOUSE_ORGANIZER) })
        )
    }

    private companion object {
        const val SITE = "https://thewallcomedy.com"
        const val HOUSE_ORGANIZER = "The Wall Comedy"
    }
}

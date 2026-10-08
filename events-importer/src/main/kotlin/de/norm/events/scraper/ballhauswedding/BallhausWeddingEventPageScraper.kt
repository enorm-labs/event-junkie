package de.norm.events.scraper.ballhauswedding

import de.norm.events.event.EventStatus
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.endOn
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import org.jsoup.nodes.Document

/**
 * Pure parser for one Wix Events page (`/details-registrierung/<slug>`), which a programme entry's `Tickets` link opens:
 * a schema.org `Event` with the start, the end, the status and the poster, and the full text under `about-section`.
 * The JSON-LD prices include the booking fee, so the programme's printed prices stay.
 */
class BallhausWeddingEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val node = document.jsonLdEvents().firstOrNull() ?: return null
        val start = node.schemaTime("startDate") ?: event.startTime
        val end = node.schemaTime("endDate")
        val text =
            document
                .select("[data-hook=about-section] p")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { null }
        return event.copy(
            description = text ?: event.description,
            startTime = start,
            endDate = end?.let { endOn(event.eventDate, start, it) } ?: event.endDate,
            endTime = end ?: event.endTime,
            imageUrl = node.schemaImageUrl() ?: event.imageUrl,
            status = if (event.status == EventStatus.SCHEDULED.name) node.schemaStatus() else event.status
        )
    }
}

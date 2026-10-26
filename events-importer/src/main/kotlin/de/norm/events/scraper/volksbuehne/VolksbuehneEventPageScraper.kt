package de.norm.events.scraper.volksbuehne

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.stringOrNull
import org.jsoup.nodes.Document

/** What one Volksbühne performance page adds to its row. */
data class VolksbuehneEventPage(
    val description: String?,
    val imageUrl: String?,
    val eventType: EventType?
) {
    /** The page's text and image; its schema.org type replaces the listing's only where the listing named no format. */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val confirmedType = eventType?.takeIf { event.typeIsFallback }
        return event.copy(
            description = description ?: event.description,
            imageUrl = imageUrl ?: event.imageUrl,
            eventType = confirmedType?.name ?: event.eventType,
            typeIsFallback = event.typeIsFallback && confirmedType == null
        )
    }
}

/**
 * Pure parser for one Volksbühne performance page (`/produktionen/<slug>/<yyyyMMdd-HHmm>/` or a
 * series evening). The text is the first `section.text-block`; the JSON-LD event carries the image
 * and a schema.org type (`DanceEvent`, `LiteraryEvent`) that names the format the listing leaves
 * out. Its `endDate` is not read: many pages give `23:00` whatever the show's length.
 */
class VolksbuehneEventPageScraper {
    fun scrape(document: Document): VolksbuehneEventPage? {
        val event = document.jsonLdEvents().firstOrNull()
        val description = description(document)
        if (event == null && description == null) return null
        return VolksbuehneEventPage(
            description = description,
            imageUrl = event?.schemaImageUrl() ?: document.attrAt("meta[property=og:image]", "content"),
            eventType = event?.stringOrNull("@type")?.let { SCHEMA_TYPES[it] }?.let { if (it == EventType.OTHER) socialEventType(description) else it }
        )
    }

    /** The performance text; also read from the English page for the second language. */
    fun description(document: Document): String? = htmlParagraphText(document.selectFirst("section.texts section.text-block .text")?.html())

    private companion object {
        /**
         * A `SocialEvent` is a ball, a dance night or a festival bar. The text tells them apart: a ballroom ball is a
         * staged competition people watch, a night on a dancefloor is a party, and the bar stays OTHER (#4009). The text
         * is read here and never stored: the source's licence withholds it.
         */
        fun socialEventType(description: String?): EventType =
            when {
                description == null -> EventType.OTHER
                BALLROOM.containsMatchIn(description) -> EventType.SHOW
                DANCE_NIGHT.containsMatchIn(description) -> EventType.PARTY
                else -> EventType.OTHER
            }

        val BALLROOM = Regex("""\bballroom|\bkiki\b""", RegexOption.IGNORE_CASE)
        val DANCE_NIGHT = Regex("""tanzfläche|dancefloor|\bparty\b""", RegexOption.IGNORE_CASE)

        /** A `SocialEvent` is resolved by [socialEventType]. */
        val SCHEMA_TYPES =
            mapOf(
                "SocialEvent" to EventType.OTHER,
                "MusicEvent" to EventType.CONCERT,
                "TheaterEvent" to EventType.SHOW,
                "DanceEvent" to EventType.SHOW,
                "ComedyEvent" to EventType.COMEDY,
                "ScreeningEvent" to EventType.SCREENING,
                "LiteraryEvent" to EventType.READING
            )
    }
}

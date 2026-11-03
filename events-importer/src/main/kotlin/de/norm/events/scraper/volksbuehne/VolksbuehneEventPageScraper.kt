package de.norm.events.scraper.volksbuehne

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document

/** What one Volksbühne performance page adds to its row. */
data class VolksbuehneEventPage(
    val description: String?,
    val imageUrl: String?,
    val eventType: EventType?,
    /** The names of the page's writer line, which names a director or an author on a staged work and the performers on a concert. */
    val writers: List<String> = emptyList()
) {
    /**
     * The page's text and image; its schema.org type replaces the listing's only where the listing named no format. A concert
     * only the page types bills its writer line when the listing billed nobody (#4438).
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val confirmedType = eventType?.takeIf { event.typeIsFallback }
        val writerActs = writers.takeIf { confirmedType == EventType.CONCERT }.orEmpty().map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }
        return event.copy(
            description = description ?: event.description,
            imageUrl = imageUrl ?: event.imageUrl,
            eventType = confirmedType?.name ?: event.eventType,
            typeIsFallback = event.typeIsFallback && confirmedType == null,
            artists = event.artists.ifEmpty { writerActs }
        )
    }
}

/**
 * Pure parser for one Volksbühne performance page (`/produktionen/<slug>/<yyyyMMdd-HHmm>/` or a
 * series evening). The text is the first `section.text-block`; the JSON-LD event carries the image
 * and a schema.org type (`DanceEvent`, `LiteraryEvent`) that names the format the listing leaves
 * out. The writer line (`p.production__writer`) is the listing's own. Its `endDate` is not read: many pages give `23:00` whatever the show's length.
 */
class VolksbuehneEventPageScraper {
    fun scrape(document: Document): VolksbuehneEventPage? {
        val event = document.jsonLdEvents().firstOrNull()
        val description = description(document)
        if (event == null && description == null) return null
        return VolksbuehneEventPage(
            writers = document.textAt("p.production__writer")?.let(VolksbuehneSpielplanScraper::splitNames).orEmpty(),
            description = description,
            imageUrl = event?.schemaImageUrl() ?: document.attrAt("meta[property=og:image]", "content"),
            eventType =
                event?.stringOrNull("@type")?.let { SCHEMA_TYPES[it] }?.let {
                    if (it == EventType.OTHER) socialEventType(description, event.stringOrNull("name")) else it
                }
        )
    }

    /** The performance text; also read from the English page for the second language. */
    fun description(document: Document): String? = htmlParagraphText(document.selectFirst("section.texts section.text-block .text")?.html())

    private companion object {
        /**
         * A `SocialEvent` is a ball, a dance night or a festival bar. The text tells them apart: a ballroom ball is a
         * staged competition people watch, a night on a dancefloor is a party, and the bar stays OTHER (#4009). Many
         * pages carry no text, so a text that decides nothing leaves it to the name: a disco or a tango night is a party.
         * The text is read here and never stored: the source's licence withholds it.
         */
        fun socialEventType(
            description: String?,
            name: String?
        ): EventType =
            when {
                description != null && BALLROOM.containsMatchIn(description) -> EventType.SHOW
                description != null && DANCE_NIGHT.containsMatchIn(description) -> EventType.PARTY
                name != null && DANCE_NIGHT_NAME.containsMatchIn(name) -> EventType.PARTY
                else -> EventType.OTHER
            }

        val BALLROOM = Regex("""\bballroom|\bkiki\b""", RegexOption.IGNORE_CASE)
        val DANCE_NIGHT = Regex("""tanzfläche|dancefloor|\bparty\b""", RegexOption.IGNORE_CASE)

        /** `Russendisko`, `Disco Night`, `Tangonacht`, `Milonga`; the suffix list keeps `Discover` and `Diskurs` out. */
        val DANCE_NIGHT_NAME = Regex("""dis[ck]o(?:\b|thek|nacht|abend)|tangonacht|\bmilonga""", RegexOption.IGNORE_CASE)

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

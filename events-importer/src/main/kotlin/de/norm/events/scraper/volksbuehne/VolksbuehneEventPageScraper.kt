package de.norm.events.scraper.volksbuehne

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.ogImageUrl
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import org.jsoup.nodes.Document
import java.time.LocalTime

/** What one Volksbühne performance page adds to its row. */
data class VolksbuehneEventPage(
    val description: String?,
    val imageUrl: String?,
    val eventType: EventType?,
    /** The names of the page's writer line, which names a director or an author on a staged work and the performers on a concert. */
    val writers: List<String> = emptyList(),
    /** The acts a concert page's second title line names, `mit A, B und C` or bare; billed when the page has no writer line (#5373). */
    val cast: List<String> = emptyList(),
    /** The house's ticket shop for this date; a sold-out category is a `<span>` with no link. */
    val ticketUrl: String? = null,
    /** The schema.org start, for a row that prints one time per ticket category and so none of its own (#5171). */
    val startTime: LocalTime? = null
) {
    /**
     * The page's text and image; its schema.org type replaces the listing's only where the listing named no format. A concert
     * only the page types bills its writer line, or else its second title line, when the listing billed nobody (#4438, #5373). A row's Tickets button often
     * links this page itself, which is no shop, so the page's shop link replaces it (#5107).
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val confirmedType = eventType?.takeIf { event.typeIsFallback }
        val pageActs =
            writers
                .ifEmpty { cast }
                .takeIf { confirmedType == EventType.CONCERT }
                .orEmpty()
                .map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }
        return event.copy(
            description = description ?: event.description,
            imageUrl = imageUrl ?: event.imageUrl,
            eventType = confirmedType?.name ?: event.eventType,
            typeIsFallback = event.typeIsFallback && confirmedType == null,
            artists = event.artists.ifEmpty { pageActs },
            startTime = event.startTime ?: startTime,
            ticketUrl = event.ticketUrl?.takeIf { isShopLink(it) && it.trimEnd('/') != event.sourceUrl.trimEnd('/') } ?: ticketUrl
        )
    }
}

private fun isShopLink(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

/**
 * Pure parser for one Volksbühne performance page (`/produktionen/<slug>/<yyyyMMdd-HHmm>/` or a
 * series evening). The text is the first `section.text-block`; the JSON-LD event carries the image
 * and a schema.org type (`DanceEvent`, `LiteraryEvent`) that names the format the listing leaves
 * out. The writer line (`p.production__writer`) is the listing's own; the second title line (`p.production__title_addition_2`) is read
 * only on a page typed as a concert. Its `endDate` is not read: many pages give `23:00` whatever the show's length.
 */
class VolksbuehneEventPageScraper {
    fun scrape(document: Document): VolksbuehneEventPage? {
        val event = document.jsonLdEvents().firstOrNull()
        val description = description(document)
        if (event == null && description == null) return null
        val eventType =
            event?.stringOrNull("@type")?.let { SCHEMA_TYPES[it] }?.let {
                if (it == EventType.OTHER) socialEventType(description, event.stringOrNull("name")) else it
            }
        return VolksbuehneEventPage(
            ticketUrl = document.select(".activity-detail__tickets a.ticket-button[href]").map { it.attr("href") }.firstOrNull(::isShopLink),
            writers = document.textAt("p.production__writer")?.let(VolksbuehneSpielplanScraper::splitNames).orEmpty(),
            cast =
                document
                    .textAt("p.production__title_addition_2")
                    ?.takeIf { eventType == EventType.CONCERT }
                    ?.let(VolksbuehneSpielplanScraper::concertNames)
                    .orEmpty(),
            startTime = event?.stringOrNull("startDate")?.substringAfter('T', "")?.let(::parseClockPrefix),
            description = description,
            imageUrl = event?.schemaImageUrl() ?: document.ogImageUrl(),
            eventType = eventType
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

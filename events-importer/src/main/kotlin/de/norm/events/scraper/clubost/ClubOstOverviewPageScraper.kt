package de.norm.events.scraper.clubost

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.clubost.ClubOstOverviewPageScraper.Companion.EVENT_INFO
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Club OST's homepage programme: a hand-built Django site whose homepage is
 * the programme, no `/events`, no month pages, no pagination; the `All` / `Current Month` /
 * `Next Month` buttons are client-side filters. The site is bilingual on `Accept-Language`, and
 * the shared scraper `WebClient`
 * ([ScraperHttpClientConfig][de.norm.events.scraper.ScraperHttpClientConfig]) sends no such
 * header and no cookies, so this parser sees only the English rendering, the one
 * [parseClubOstDate] and [parseClubOstTime] implement; a shared `Accept-Language` on that client
 * would silently switch it.
 *
 * Every card is a flyer, a title, a start time and a Resident Advisor link, identified by the id
 * in its `/event/<id>` link (a UUID since September 2026, numeric before). A techno club, so every
 * event is [EventType.PARTY], as [gartn][de.norm.events.scraper.gartn] and
 * [voidclub][de.norm.events.scraper.voidclub] do. The template reserves an empty `div.artist`
 * slot, blank on every event so far, the bills living on Resident Advisor.
 *
 * @see CLUB_OST_LIMITATIONS for what the source does not publish.
 * @see ClubOstDetailPageScraper for the primary per-event data source.
 */
class ClubOstOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every card on the homepage.
     *
     * @param sourceUrl the URL the document was fetched from, to resolve relative links.
     * @return the events discovered; cards without a usable link, title or date are skipped with a
     * warning.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select(EVENT_CARD)
        logger.info { "Found ${cards.size} event card(s) on the Club OST homepage" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip a single malformed card without aborting the import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, sourceUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Club OST event card, skipping" }
                null
            }
        }
    }

    /**
     * Parses one `.event-item` card, or `null` when a required field is missing. The markup nests
     * the Resident Advisor link inside the anchor wrapping the card; nested anchors are invalid, so
     * the HTML5 adoption-agency algorithm splits the outer anchor into siblings, and every selector
     * matches from the `.event-item` container rather than that anchor.
     */
    @Suppress("ReturnCount") // Guard clauses per missing required field read better than a nested let-chain
    private fun parseCard(
        card: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val href = card.attrAt(DETAIL_LINK, "href")
        if (href.isNullOrBlank()) {
            logger.warn { "Club OST card has no detail link, skipping" }
            return null
        }
        val eventId = extractClubOstEventId(href)
        if (eventId == null) {
            logger.warn { "Club OST detail link '$href' carries no event id, skipping" }
            return null
        }

        val title = card.textAt("h3")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "Club OST card at '$href' has no title, skipping" }
            return null
        }

        // "Aug. 7, 2026 | 11 p.m." — one span holding both halves, separated by a pipe.
        val info = card.textAt(EVENT_INFO).orEmpty()
        val eventDate = parseClubOstDate(info.substringBefore(INFO_SEPARATOR))
        if (eventDate == null) {
            logger.warn { "Could not parse a date from '$info' for '$title', skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            eventDate = eventDate,
            startTime = parseClubOstTime(info.substringAfter(INFO_SEPARATOR, "")),
            eventType = EventType.PARTY.name,
            // The venue names no style but programmes techno, so the venue is the default, as at Tresor.
            genre = "Techno",
            // A card with no flyer falls back to the house logo on a site-relative /static path, which
            // imgSrcAt rejects as non-absolute.
            imageUrl = card.imgSrcAt("img.event-image"),
            sourceUrl = resolveUrl(sourceUrl, href),
            sourceId = "${EventSource.CLUB_OST.sourceIdPrefix}$eventId",
            ticketUrl = card.hrefAt(TICKET_LINK)
        )
    }

    companion object {
        /** One event card in the homepage grid. */
        private const val EVENT_CARD = ".event-item"

        /** The card's link to its own detail page — `/event/<id>/`. */
        private const val DETAIL_LINK = "a[href*=/event/]"

        /**
         * The Resident Advisor link, matched by the badge it wraps via `:has()`: the detail link carries
         * neither a badge nor a stable class.
         */
        private const val TICKET_LINK = "a:has(span.tag-evento)"

        /** The footer line of a card, holding the date and start time. */
        private const val EVENT_INFO = ".event-info"

        /** Separator between the date and the start time inside [EVENT_INFO]. */
        private const val INFO_SEPARATOR = "|"
    }
}

/**
 * The event id from the detail URL: `/event/231438/` to `231438`, and since September 2026
 * `/event/e9bdde1e-299a-4cc3-ad01-c8d3011aa869/` to that UUID. The booking-system primary key,
 * stable where the upper-cased title is not; there is no slug. Both shapes accepted: a pattern
 * taking only leading digits skipped every UUID starting with a letter and collided the rest on
 * their first digits, losing two of ten September cards (#1131). `null` otherwise, the signal to
 * skip the card.
 */
fun extractClubOstEventId(href: String): String? = EVENT_ID_PATTERN.find(href)?.groupValues?.get(1)

/** The id segment of a Club OST detail path — a UUID or the older numeric key — with or without the trailing slash. */
private val EVENT_ID_PATTERN = Regex("""/event/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|\d+)/?(?:$|[?#/])""", RegexOption.IGNORE_CASE)

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
 * Pure HTML parser for Club OST's homepage programme.
 *
 * The club runs a small hand-built Django site whose **homepage is the programme page** — there is no
 * `/events` route, no month pages and no pagination, so a single fetch sees the whole announced
 * season. The `All` / `Current Month` / `Next Month` buttons above the grid are client-side filters
 * over that one already-rendered list, not links to further pages, so ignoring them misses nothing.
 *
 * **The site is bilingual on `Accept-Language`.** Django's `LocaleMiddleware` renders the date one way
 * for its default locale and another for German. The shared scraper `WebClient`
 * ([ScraperHttpClientConfig][de.norm.events.scraper.ScraperHttpClientConfig]) sends no
 * `Accept-Language` header and no cookies, so the middleware falls through to the site's default and
 * this parser only ever sees the English rendering — which is why [parseClubOstDate] and
 * [parseClubOstTime] implement only that one. Adding a shared `Accept-Language` to that client would
 * silently switch the rendering and must come with a matching parser here.
 *
 * Every card is a flyer, a title, a start time and a Resident Advisor ticket link, identified by the
 * numeric id in its `/event/<id>` link. The venue is a techno club whose whole programme is club
 * nights, so every event is typed [EventType.PARTY] outright — the same call
 * [gartn][de.norm.events.scraper.gartn], [voidclub][de.norm.events.scraper.voidclub] and the other
 * category-less techno rooms make. The template does reserve a slot for the lineup — an empty
 * `div.artist`, blank on every event so far, the bills living on the linked Resident Advisor pages —
 * which is where one would come from should the venue start filling it in.
 *
 * @see CLUB_OST_LIMITATIONS for what the source does not publish.
 * @see ClubOstDetailPageScraper for the primary per-event data source.
 */
class ClubOstOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event card on the homepage.
     *
     * @param sourceUrl the URL the document was fetched from, used to resolve the relative
     *   detail-page links.
     * @return the events discovered on the page; cards without a usable link, title or date
     *   are skipped with a warning rather than persisted half-built.
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
     * Parses one `.event-item` card into a [ScrapedEvent], or `null` when it is missing a
     * field the event cannot be stored without.
     *
     * The card's markup nests the Resident Advisor ticket link *inside* the anchor wrapping
     * the whole card. Nested anchors are invalid HTML, so the parser applies the HTML5
     * adoption-agency algorithm and splits the outer anchor into siblings — every selector
     * below therefore matches from the `.event-item` container rather than by walking down
     * from that anchor, which no longer holds the card's contents in the parsed tree.
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
            logger.warn { "Club OST detail link '$href' carries no numeric event id, skipping" }
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
            // A card with no flyer uploaded falls back to the house logo, served from a
            // site-relative /static path — imgSrcAt only accepts absolute URLs, so the
            // placeholder drops out here without needing to be matched by name.
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
         * The Resident Advisor ticket link, matched by the badge it wraps rather than by its
         * `target`/`href`: the card's own detail link carries neither a badge nor a stable
         * class, so `:has()` on the badge is what separates the two anchors.
         */
        private const val TICKET_LINK = "a:has(span.tag-evento)"

        /** The footer line of a card, holding the date and start time. */
        private const val EVENT_INFO = ".event-info"

        /** Separator between the date and the start time inside [EVENT_INFO]. */
        private const val INFO_SEPARATOR = "|"
    }
}

/**
 * Extracts the numeric event id Club OST's detail URLs carry — `/event/231438/` → `231438`.
 *
 * The id is the club's booking-system primary key (it reappears in the flyer's S3 path,
 * `…/756/231438/original/…`), so it is stable across renames and re-scheduling in a way the
 * upper-cased title is not. It is the whole of the event's identity on this site: there is no
 * slug. Returns `null` when the href has no numeric segment, which is the signal to skip the
 * card rather than mint an unstable `sourceId` from the title.
 */
fun extractClubOstEventId(href: String): String? = EVENT_ID_PATTERN.find(href)?.groupValues?.get(1)

/** The numeric id segment of a Club OST detail path, with or without the trailing slash. */
private val EVENT_ID_PATTERN = Regex("""/event/(\d+)/?""")

package de.norm.events.scraper.migas

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseIsoTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Parses migas' WordPress programme page (`/program/`) into [ScrapedEvent]s.
 *
 * The custom `migas` theme renders every upcoming event **twice** into `.events-list`: a
 * summary anchor (`a.event-item`) followed by a sibling modal (`div.event-popup`) holding the
 * full record. The anchor's `href` is `#` — the modal is opened client-side — so there is no
 * detail page to follow and this is a single-page importer. The two halves are joined by the
 * anchor's `data-target`, which is the modal's `#popup-<wp-post-id>` selector rather than
 * document order, so a template that reorders or nests them still parses.
 *
 * The modal is the richer half and supplies every field except the category, which is read
 * from the anchor. Two of its attributes are what make this source cheap and durable:
 *
 *  - **`button[data-target=add-to-calendar]`'s `data-start-date`** carries a full ISO-8601 offset
 *    datetime. The human rendering beside it — and the anchor's date block — is year-less
 *    (`we · 05.08 · 20:00`), so this attribute is the only place the year is stated, which avoids the
 *    weekday-based year inference the retro listings need ([inferYearForWeekday]).
 *  - **`button[data-target=share]`'s `data-url`** is the event's canonical permalink
 *    (`https://migas.berlin/event/<slug>/`), which supplies both `sourceUrl` and the stable
 *    `sourceId` slug.
 *
 * Traps:
 *  - **Images are lazy-loaded**: every `<img>`'s `src` is an inline SVG placeholder data URI
 *    and the real file is in `data-src`. Reading `src` (i.e. the shared
 *    [imgSrcAt][de.norm.events.scraper.imgSrcAt] helper) would store a base64 placeholder as
 *    the poster for every event, so this scraper reads `data-src` explicitly.
 *  - **An event without a share permalink is skipped, not re-keyed.** `sourceId` is the
 *    identity used for idempotent upserts, so falling back to a second scheme (the WordPress
 *    post id on the modal, say) when the share button is missing would re-key the *whole*
 *    programme at once — inserting duplicates and letting stale-cleanup delete the originals.
 *    Dropping the event with a warning is the safer failure.
 *
 * **What the source does not carry:** no prices, ticket links, door times, or sold-out and
 * cancellation badges — entry arrangements are not stated on the site at all, so `free` is left to the
 * shared price-based derivation rather than guessed. There is one time per event, taken as
 * `startTime`. The genre field is left empty: the venue's two categories are *formats*, not genres,
 * and the real genre prose ("Somali funk, Ethio-jazz, Sudanese pop") appears only in the description.
 *
 * @see MigasWebsiteImporter for the fetch side, including why conditional requests are disabled.
 */
class MigasOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event in [document]'s programme list.
     *
     * Per-event parsing is wrapped so one malformed entry cannot abort the import; events
     * without a resolvable date or permalink are skipped with a warning rather than persisted
     * half-formed.
     */
    fun scrape(document: Document): List<ScrapedEvent> =
        document.select(EVENT_ITEM_SELECTOR).mapNotNull { item ->
            runCatching { parseEvent(item, document) }
                .onFailure { logger.warn(it) { "Skipping unparseable migas event" } }
                .getOrNull()
        }

    @Suppress("ReturnCount") // Guard clauses for the three required fields read better than nesting.
    private fun parseEvent(
        item: Element,
        document: Document
    ): ScrapedEvent? {
        val popup = item.popup(document)
        if (popup == null) {
            logger.warn { "migas event has no matching popup for ${item.attr(POPUP_TARGET_ATTRIBUTE)}" }
            return null
        }

        val sourceUrl = popup.attrAt(SHARE_BUTTON_SELECTOR, SHARE_URL_ATTRIBUTE)
        if (sourceUrl == null) {
            logger.warn { "migas event has no share permalink, cannot build a stable sourceId" }
            return null
        }

        val start = parseStart(popup.attrAt(CALENDAR_BUTTON_SELECTOR, START_DATE_ATTRIBUTE))
        if (start == null) {
            logger.warn { "migas event $sourceUrl has no parseable start date" }
            return null
        }

        val rawTitle = item.textAt(TITLE_SELECTOR) ?: popup.textAt(POPUP_TITLE_SELECTOR)
        val title = rawTitle?.let { cleanEventTitle(it) }?.takeIf { it.isNotBlank() }
        if (title == null) {
            logger.warn { "migas event $sourceUrl has no title" }
            return null
        }

        val category = item.textAt(CATEGORY_SELECTOR) ?: popup.textAt(POPUP_CATEGORY_SELECTOR)
        val (eventDate, startTime) = start

        return ScrapedEvent(
            title = title,
            description = popup.textAt(DESCRIPTION_SELECTOR),
            eventType = mapEventType(category, EVENT_TYPE_SYNONYMS),
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = popup.lazyImage(POPUP_IMAGE_SELECTOR) ?: item.lazyImage(ITEM_IMAGE_SELECTOR),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MIGAS.sourceIdPrefix}${extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)}",
            artists = artistsFor(title)
        )
    }

    /** Resolves the modal this summary anchor points at, via its `data-target` (`#popup-<id>`) selector. */
    private fun Element.popup(document: Document): Element? =
        attr(POPUP_TARGET_ATTRIBUTE)
            .takeIf { it.startsWith("#") }
            ?.let { document.selectFirst(it) }

    /**
     * Reads the real poster URL from the first matching `<img>`'s `data-src`.
     *
     * The theme's lazy-loader keeps an inline SVG placeholder in `src` until the image scrolls
     * into view, so `data-src` is the only attribute holding a fetchable URL.
     */
    private fun Element.lazyImage(cssQuery: String): String? =
        attrAt(cssQuery, LAZY_IMAGE_ATTRIBUTE)
            ?.takeIf { it.startsWith("http") }

    /**
     * Splits the calendar button's ISO-8601 offset datetime into a date and a local time.
     *
     * The observed spelling carries an offset (`2026-08-05T20:00:00+02:00`), which the shared
     * [parseIsoTime] cannot read — its `HH:mm` formatter rejects both the seconds and the
     * trailing offset — so [OffsetDateTime] is tried first. The shared helpers remain the
     * fallback so a future offset-less or date-only spelling still yields a date.
     */
    private fun parseStart(value: String?): Pair<LocalDate, LocalTime?>? {
        if (value.isNullOrBlank()) return null
        val offsetDateTime = runCatching { OffsetDateTime.parse(value) }.getOrNull()
        return offsetDateTime?.let { it.toLocalDate() to it.toLocalTime() }
            ?: parseIsoDate(value)?.let { it to parseIsoTime(value) }
    }

    /**
     * Builds the lineup from the event title, which is the booked selector's name
     * ("vip client", "eric.a & llupe" — co-bills are split by [headlinersFromTitle]).
     *
     * Everyone migas books plays records rather than performing live, so the role is always
     * `DJ`. An album-playback night ([isAlbumPlayback]) yields **no** artists: its title names
     * the record being played, and minting "Kyuss" as an artist on it would assert that the
     * band appears at the venue.
     */
    private fun artistsFor(title: String): List<ScrapedArtist> =
        if (isAlbumPlayback(title)) {
            emptyList()
        } else {
            headlinersFromTitle(title).map { it.copy(role = ARTIST_ROLE_DJ) }
        }

    private companion object {
        /** The summary anchors; the class is exact-token matched, so `.event-item-date` and friends don't collide. */
        const val EVENT_ITEM_SELECTOR = ".events-list a.event-item"

        const val TITLE_SELECTOR = ".event-item-title"
        const val CATEGORY_SELECTOR = ".event-item-category"
        const val ITEM_IMAGE_SELECTOR = ".event-item-media img"
        const val POPUP_TITLE_SELECTOR = ".event-popup-content p"
        const val POPUP_CATEGORY_SELECTOR = ".event-popup-header h3"
        const val POPUP_IMAGE_SELECTOR = ".event-popup-media img"
        const val DESCRIPTION_SELECTOR = ".event-popup-content .wysiwyg"
        const val CALENDAR_BUTTON_SELECTOR = "button[data-target=add-to-calendar]"
        const val SHARE_BUTTON_SELECTOR = "button[data-target=share]"

        const val POPUP_TARGET_ATTRIBUTE = "data-target"
        const val START_DATE_ATTRIBUTE = "data-start-date"
        const val SHARE_URL_ATTRIBUTE = "data-url"
        const val LAZY_IMAGE_ATTRIBUTE = "data-src"

        /** Permalink path prefix stripped to leave the event slug: `/event/sitaad/` → `sitaad`. */
        const val EVENT_PATH_PREFIX = "/event/"

        /** Every act migas books plays records, so the lineup role is always a DJ set. */
        const val ARTIST_ROLE_DJ = "DJ"

        /**
         * migas' own two programme categories. Neither maps onto an existing [EventType]
         * synonym, and both describe the *format* of the night:
         *  - `playing` — a booked selector plays a record set, the closest thing this model
         *    has to [EventType.CLUB_NIGHT]. Deliberately not `PARTY`, which would describe a
         *    seated listening bar as a dance floor: what a visitor comes here for is the act
         *    on the decks, which is exactly the distinction `CLUB_NIGHT` draws
         *    (EVENT_SCOPE.md §2).
         *  - `listening session` — a guest session or a full-album playback, neither a concert
         *    (nobody performs) nor a club night, so it stays [EventType.OTHER].
         *
         * **This choice costs no lineup either way, contrary to what this KDoc used to claim.**
         * It said `PARTY` would make
         * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType] drop the
         * artists — true of that function, but [artistsFor] never calls it; it goes straight to
         * [headlinersFromTitle][de.norm.events.scraper.headlinersFromTitle], which ignores the
         * event type. That early return is the only place in the importer where a type
         * suppresses a lineup, so retyping these nights would leave their artists untouched.
         * Measured while establishing what that rule actually costs; recorded here
         * because a right decision resting on a wrong reason is one re-reading away from being
         * reversed for the wrong reason too.
         */
        val EVENT_TYPE_SYNONYMS =
            mapOf(
                "playing" to EventType.CLUB_NIGHT.name,
                "listening session" to EventType.OTHER.name
            )

        /**
         * A full-album playback title: an act/album separator followed by a trailing
         * release parenthetical carrying a four-digit year — `"SBTRKT – SBTRKT (Young, 2011 •
         * 43 min • vinyl)"`, `"Kyuss – Blues for the Red Sun (Elektra/Asylum Records, 1992 •
         * 52 min • vinyl)"`.
         *
         * Requiring *both* halves keeps it off an ordinary booked act whose name merely
         * contains a dash, and off a title that happens to end in a parenthetical without a
         * year.
         */
        val ALBUM_PLAYBACK_PATTERN =
            Regex("""\s[–—-]\s.*\([^()]*\b(?:19|20)\d{2}\b[^()]*\)\s*$""")

        fun isAlbumPlayback(title: String): Boolean = ALBUM_PLAYBACK_PATTERN.containsMatchIn(title)
    }
}

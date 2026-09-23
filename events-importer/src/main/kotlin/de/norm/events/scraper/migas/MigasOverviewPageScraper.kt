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
 * summary anchor (`a.event-item`) whose `href` is `#`, then a sibling modal (`div.event-popup`)
 * with the full record. They are joined by the anchor's `data-target` — the modal's
 * `#popup-<wp-post-id>` selector — not document order, so a reordered or nested template still parses.
 *
 * The modal supplies every field except the category, read from the anchor. Two of its button
 * attributes carry what nothing else states: `data-start-date` on the add-to-calendar button
 * is a full ISO-8601 offset datetime and the only place the year appears — every human
 * rendering is year-less (`we · 05.08 · 20:00`) — so no [inferYearForWeekday] here; `data-url`
 * on the share button is the canonical permalink, supplying `sourceUrl` and the `sourceId` slug.
 *
 * **Images are lazy-loaded**: every `<img>`'s `src` is an inline SVG placeholder and the real
 * file is in `data-src`, so the shared [imgSrcAt][de.norm.events.scraper.imgSrcAt] would store
 * a base64 placeholder as every poster.
 *
 * **An event without a share permalink is skipped, not re-keyed.** `sourceId` is the identity
 * idempotent upserts turn on; a second scheme — the WordPress post id, say — would re-key the
 * *whole* programme, inserting duplicates and letting stale-cleanup delete the originals.
 * Dropping with a warning is the safer failure.
 *
 * One time per event, taken as `startTime`. The genre stays empty though the venue writes real
 * genre prose ("Somali funk, Ethio-jazz, Sudanese pop") into descriptions: its two categories
 * are *formats*, and mining prose for a genre is not something this parser guesses at.
 *
 * @see MigasWebsiteImporter for the fetch side, including why conditional requests are disabled.
 */
class MigasOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every event in [document]'s programme list. Per-event parsing is wrapped so one
     * malformed entry cannot abort the import; events without a resolvable date or permalink are
     * skipped with a warning rather than persisted half-formed.
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
     * The real poster URL from the first matching `<img>`'s `data-src`. The lazy-loader keeps an
     * inline SVG placeholder in `src` until the image scrolls into view.
     */
    private fun Element.lazyImage(cssQuery: String): String? =
        attrAt(cssQuery, LAZY_IMAGE_ATTRIBUTE)
            ?.takeIf { it.startsWith("http") }

    /**
     * Splits the calendar button's ISO-8601 offset datetime into date and local time. The observed
     * spelling carries an offset (`2026-08-05T20:00:00+02:00`), which the shared [parseIsoTime]
     * cannot read — its `HH:mm` formatter rejects the seconds and the offset — so
     * [OffsetDateTime] is tried first; the shared helpers remain the fallback so an offset-less or
     * date-only spelling still yields a date.
     */
    private fun parseStart(value: String?): Pair<LocalDate, LocalTime?>? {
        if (value.isNullOrBlank()) return null
        val offsetDateTime = runCatching { OffsetDateTime.parse(value) }.getOrNull()
        return offsetDateTime?.let { it.toLocalDate() to it.toLocalTime() }
            ?: parseIsoDate(value)?.let { it to parseIsoTime(value) }
    }

    /**
     * The lineup from the title, the booked selector's name ("vip client", "eric.a & llupe" —
     * co-bills split by [headlinersFromTitle]). Everyone migas books plays records, so the role
     * is always `DJ`. An album-playback night ([isAlbumPlayback]) yields **no** artists: its
     * title names the record, and minting "Kyuss" would assert the band appears at the venue.
     */
    private fun artistsFor(title: String): List<ScrapedArtist> =
        if (isAlbumPlayback(title)) {
            emptyList()
        } else {
            headlinersFromTitle(title).map { it.copy(role = ARTIST_ROLE_DJ) }
        }

    private companion object {
        /** The summary anchors; exact-token matched, so `.event-item-date` and friends don't collide. */
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
         * migas' two programme categories, both describing the *format* of the night, neither an
         * existing [EventType] synonym:
         * - `playing` — a booked selector plays a record set, which is what [EventType.PARTY]
         *   holds at every other venue (#1783). The selector stays billed: only
         *   [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType] drops
         *   artists for `PARTY`, and [artistsFor] never calls it.
         * - `listening session` — a guest session or full-album playback, neither a concert (nobody
         *   performs) nor a party, so [EventType.OTHER].
         */
        val EVENT_TYPE_SYNONYMS =
            mapOf(
                "playing" to EventType.PARTY.name,
                "listening session" to EventType.OTHER.name
            )

        /**
         * A full-album playback title: an act/album separator followed by a trailing release
         * parenthetical with a four-digit year — `"SBTRKT – SBTRKT (Young, 2011 • 43 min • vinyl)"`,
         * `"Kyuss – Blues for the Red Sun (Elektra/Asylum Records, 1992 • 52 min • vinyl)"`. Requiring
         * *both* halves keeps it off a booked act whose name contains a dash, and off a title ending
         * in a parenthetical without a year.
         */
        val ALBUM_PLAYBACK_PATTERN =
            Regex("""\s[–—-]\s.*\([^()]*\b(?:19|20)\d{2}\b[^()]*\)\s*$""")

        fun isAlbumPlayback(title: String): Boolean = ALBUM_PLAYBACK_PATTERN.containsMatchIn(title)
    }
}

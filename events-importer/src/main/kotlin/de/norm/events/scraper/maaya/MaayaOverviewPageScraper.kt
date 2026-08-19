package de.norm.events.scraper.maaya

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for MAAYA Berlin's home-page **NEXT DATES** programme.
 *
 * The venue runs WordPress with Elementor and has no events plugin at all — `/wp-json/wp/v2/types`
 * lists no event post type, and the only other dated-looking pages (`/maaya-calendar/`,
 * `/special-events/`) render no dates. The programme is hand-built Elementor widgets on the home
 * page, so this one section is the whole source: no per-event pages, no detail text, no prices and
 * no lineups. Every event's `sourceUrl` is therefore the home page itself, and its identity is the
 * resolved date plus the slugified title.
 *
 * The section is anchored on the `#events` id rather than on Elementor's generated
 * `elementor-element-<hash>` classes, which change whenever the page is re-saved. Each card is one
 * Elementor column holding a poster, an `h4` heading, a one-line schedule and a button.
 *
 * Four things about this section shape the parser:
 *
 * 1. **It mixes standing opening hours in with the dated programme.** "MAAYA POOL DAY — Tue. to
 *    Sat. from 12:00 pm to 5:00 pm" and "LUNCH AT MAAYA" are the venue's daytime offering, not
 *    events, and they name no date. Cards without a `dd.MM.yyyy` are skipped, which drops exactly
 *    those.
 * 2. **The clock is 24-hour with a decorative `pm` glued to every time**, whatever the hour — a
 *    daytime party reads "from 11:00pm – 17:00pm". The meridiem is therefore meaningless and is
 *    ignored; only the leading `HH:mm` is read. The stated end time is dropped because the data
 *    model has no field for it.
 * 3. **The weekday label is unreliable**, so it is not read at all: the venue writes
 *    "Thu. 05.08.2026" for a Wednesday. The explicit four-digit date is unambiguous on its own, so
 *    unlike the year-less retro listings there is nothing for a weekday to disambiguate.
 * 4. **The button is both the ticket link and the entry note.** It links out to Xceed or
 *    rausgegangen where an event is ticketed, and carries the venue's own entry wording otherwise
 *    ("FREE ENTRY", "FREE ENTRY WITH 10€ VOUCHER", "TICKETS AT THE DOOR"). See [entryNoteOf]. The
 *    link is taken as the venue states it, mistakes included — both halves of a two-part night can
 *    point at the same shop page (the Rave the Planet truck and its afterparty do).
 *
 * Beyond that the card carries nothing: **no doors time, no description, no genre and no numeric
 * price** — the entry note is as close to a price as the venue publishes, so `pricePresale` and
 * `priceBoxOffice` are never set. The stated end of the time range is dropped for want of a field.
 *
 * **No artists are minted.** The venue publishes no lineup field, and its titles are series and
 * party names ("SUPAFLY", "LA ISLA MAAYA X FURIOSA", "RIPPLES W/ AMINE K") rather than acts, so
 * deriving a headliner from the title would file party names in the artist table. For the same
 * reason the type falls back to `OTHER` via [inferUnmarkedTitleType] rather than to `PARTY`: MAAYA
 * is a multi-format house — gallery, garden, pool and market — whose dated programme mixes club
 * nights with concerts and workshops, so a cue-less title is genuinely unknown rather than
 * presumed a club night the way Crack Bellmer's is.
 *
 * @see MaayaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://maaya.de/">MAAYA Berlin</a>
 */
class MaayaOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every dated event out of the home page's **NEXT DATES** section.
     *
     * @param baseUrl the URL the document was fetched from; stored as every event's `sourceUrl`,
     *   since the venue publishes no per-event pages.
     * @return a list of [ScrapedEvent] instances in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val section = document.selectFirst(PROGRAMME_SECTION)
        if (section == null) {
            logger.warn { "No '$PROGRAMME_ANCHOR' section on the MAAYA home page — the programme block moved or was renamed" }
            return emptyList()
        }

        val cards = section.select(EVENT_CARD)
        logger.info { "Found ${cards.size} programme card(s) in the MAAYA NEXT DATES section" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse MAAYA programme card, skipping" }
                null
            }
        }
    }

    /**
     * Parses one programme card into a [ScrapedEvent], or `null` when it has no title or no date.
     *
     * A dateless card is the venue's standing opening hours rather than a malformed event, so it is
     * dropped quietly — logging a warning per import for the two permanent ones would be noise.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = card.textAt(TITLE)?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) return null

        val schedule = card.textAt(SCHEDULE).orEmpty()
        val eventDate = parseEventDate(schedule) ?: return null

        val entryNote = entryNoteOf(card)
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title),
            eventDate = eventDate,
            startTime = parseStartTime(schedule),
            imageUrl = card.imgSrcAt(POSTER),
            // No per-event pages exist, so the listing itself is the canonical URL.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.MAAYA.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = card.hrefAt(BUTTON),
            // A bare "FREE ENTRY" is fully carried by the free flag, so storing it again as a note
            // would only repeat it; a qualified one is not, and is kept verbatim.
            priceNote = entryNote?.takeUnless { it.equals(FREE_ENTRY_LABEL, ignoreCase = true) },
            free = detectFree(priceNote = entryNote, title = title)
        )
    }

    /**
     * The venue's own entry wording, or `null` when the button says nothing about it.
     *
     * The same button serves both roles, so its label is only an entry note when it is not a bare
     * call to action: "TICKETS" and "RESERVATIONS" name the link, whereas "FREE ENTRY WITH 10€
     * VOUCHER" and "TICKETS AT THE DOOR" state the terms. The label is stored as written — the
     * venue's phrasing is the most precise thing available, since it publishes no numeric prices —
     * and the shared [detectFree] reads the free flag off it.
     */
    private fun entryNoteOf(card: Element): String? = card.textAt(BUTTON_LABEL)?.takeUnless { it.uppercase() in CALL_TO_ACTION_LABELS }

    /** Reads the card's `dd.MM.yyyy` date, or `null` when the line states none (a standing offer). */
    private fun parseEventDate(schedule: String): LocalDate? = parseGermanDate(DATE_PATTERN.find(schedule)?.value)

    /**
     * Reads the first `HH:mm` of the schedule line as the start time, padding a single-digit hour
     * so the shared [parseTime] accepts it. The trailing meridiem is deliberately not consulted —
     * see the class KDoc.
     */
    private fun parseStartTime(schedule: String): LocalTime? {
        val match = TIME_PATTERN.find(schedule) ?: return null
        val (hour, minute) = match.destructured
        return parseTime("%02d:%s".format(hour.toInt(), minute))
    }

    private companion object {
        /** The id Elementor puts on the NEXT DATES block — stable, unlike its generated element classes. */
        const val PROGRAMME_ANCHOR = "#events"

        /** The top-level section wrapping the NEXT DATES heading and every card below it. */
        const val PROGRAMME_SECTION = "section.elementor-top-section:has($PROGRAMME_ANCHOR)"

        /** One event card: an Elementor column inside one of the section's inner rows. */
        const val EVENT_CARD = "section.elementor-inner-section .elementor-column > .elementor-widget-wrap"

        const val TITLE = ".elementor-widget-heading .elementor-heading-title"
        const val SCHEDULE = ".elementor-widget-text-editor"
        const val POSTER = ".elementor-widget-image img"
        const val BUTTON = ".elementor-widget-button a.elementor-button"
        const val BUTTON_LABEL = ".elementor-widget-button .elementor-button-text"

        /** Button labels that name the link rather than the entry terms, so carry no pricing information. */
        val CALL_TO_ACTION_LABELS = setOf("TICKETS", "RESERVATIONS")

        /** The unqualified free-entry label, whose meaning the `free` flag already carries. */
        const val FREE_ENTRY_LABEL = "FREE ENTRY"

        /** The venue's `dd.MM.yyyy` date rendering, tolerating single-digit day and month. */
        val DATE_PATTERN = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")

        /** A 24-hour clock reading; the venue's trailing `am`/`pm` is decorative and not captured. */
        val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")
    }
}

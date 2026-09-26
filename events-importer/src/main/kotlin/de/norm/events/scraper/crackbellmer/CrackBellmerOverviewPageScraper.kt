package de.norm.events.scraper.crackbellmer

import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for Crack Bellmer's Webflow programme listing.
 *
 * Every night is an `.event-item` in one Finsweet CMS list: a `data-date`, an `h:mm a` start
 * time, the title, a comma-separated genre line, a comma-separated lineup line, and a poster.
 * Its `/events/<slug>` page adds only a prose blurb, read by [CrackBellmerDetailPageScraper].
 *
 * 1. **`data-date` is the only place the year is written.** The calendar column renders
 * `Fri . 7 . 8 .`; the attribute carries the full `August 7, 2026` — and ADR-007 ranks a
 * `data-*` attribute above a class name anyway.
 * 2. **The list is the whole published programme, not a month.** The `previous-month`,
 * `this-month` and `next-month` tabs serve identical markup filtered client-side, so the
 * listing carries about a month of passed nights. Those are dropped here, before the detail
 * fetch, so no HTTP is wasted on events persistence would discard ([dropPastEvents]).
 * 3. **No event category.** The genre line ("Techno, House", but also "Drag Show", "Concert
 * meets Pub Quiz") is the only cue, so the type is read from the title and then the genre
 * with the shared keyword classifier, defaulting to `PARTY` — a dance bar of DJ nights, so a
 * cue-less night is one of those.
 * 4. **A poster-less night still renders an `<img>`**, pointing at Webflow's placeholder SVG
 * and flagged `w-dyn-bind-empty`; the same flag marks an empty genre or lineup paragraph.
 *
 * @see CRACK_BELLMER_LIMITATIONS for what the venue does not publish.
 * @see CrackBellmerWebsiteImporter for the HTTP fetch orchestrator.
 */
class CrackBellmerOverviewPageScraper(
    /** Clock for the past-event cutoff; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the programme document.
     *
     * @param sourceUrl the URL the document was fetched from, for the relative `/events/<slug>` links.
     * @return the upcoming [ScrapedEvent] instances (today onward) in listing order.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("[role=list] .event-item")
        logger.info { "Found ${items.size} event item(s) on Crack Bellmer programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed items without aborting the whole import
        val events =
            items.mapNotNull { item ->
                try {
                    parseItem(item, sourceUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Crack Bellmer event item, skipping" }
                    null
                }
            }

        return events.dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from the Crack Bellmer listing" }
        }
    }

    /** Parses one `.event-item` into a [ScrapedEvent], or `null` when unusable or not an event. */
    @Suppress("ReturnCount") // Guard clauses for the required link/title/date and the closed-day marker are clearer than nesting
    private fun parseItem(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val href = item.attrAt("a[href*=/events/]", "href") ?: error("No event link found")
        val title = item.textAt("[fs-list-field=title]")?.let(::cleanEventTitle) ?: error("No title found")
        // Closed days are programme entries. They name no event, so storing one would put a night
        // titled "CLOSED" in the calendar.
        if (title.equals(CLOSED_MARKER, ignoreCase = true)) return null

        val eventDate = parseDate(item)
        if (eventDate == null) {
            logger.warn { "No parseable date for Crack Bellmer event '$title', skipping" }
            return null
        }

        val genre = item.textAt("[fs-list-field=genre]")
        return ScrapedEvent(
            title = title,
            eventType = classifyEventType(title, genre),
            eventDate = eventDate,
            startTime = parseTime(item.textAt(".main-heading.is-time"), TIME_FORMATTER),
            imageUrl = item.imgSrcAt(".event-image-wrapper img:not(.w-dyn-bind-empty)"),
            sourceUrl = resolveUrl(sourceUrl, href),
            sourceId = "${EventSource.CRACK_BELLMER.sourceIdPrefix}${href.substringAfter(EVENT_PATH).trimEnd('/')}",
            genre = genre,
            artists = parseArtists(item.textAt("[fs-list-field=lineup]"))
        )
    }

    /** The item's `data-date` (`August 7, 2026`), the only rendering that carries the year. */
    private fun parseDate(item: Element): LocalDate? {
        val text = item.attr(DATE_ATTRIBUTE).takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDate.parse(text.trim(), DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Types an event from its [title] and then its [genre], defaulting to `PARTY`.
     *
     * No category at all, so both go through the shared [inferUnmarkedTitleType] keyword
     * classifier — including the genre line, where this venue names a non-musical format
     * ("Concert meets Pub Quiz" → `QUIZ`) while the title stays a bare event name. The fallback
     * is `PARTY`, not [inferConcertVenueType][de.norm.events.scraper.inferConcertVenueType]'s
     * `CONCERT` or [inferUnmarkedTitleType]'s `OTHER`: a dance bar programming DJ nights, so a
     * night with no cue is one of those, not a gig and not an unknown.
     */
    private fun classifyEventType(
        title: String,
        genre: String?
    ): String {
        val fromTitle = inferUnmarkedTitleType(title)
        if (fromTitle != EventType.OTHER.name) return fromTitle
        val fromGenre = genre?.let(::inferUnmarkedTitleType)
        return fromGenre?.takeIf { it != EventType.OTHER.name } ?: EventType.PARTY.name
    }

    /**
     * The night's acts from its lineup line.
     *
     * A flat, comma-separated billing, so the shared [splitSupportActs] applies — after two venue
     * spellings are normalised: `w/` introduces the acts a host plays with ("hosted by Nicole M
     * Pikole w/ KumKween & Slaxy Lexy"), and `b2b` joins two DJs into one slot; both open a new act.
     *
     * Roles come from the venue's annotations, the only distinction it draws: `(live)` / trailing
     * `LIVE` marks a band, `hosted by …` / `(Host)` whoever fronts the night — both `HEADLINER`.
     * Everything else is a `DJ` booking, the closest the three-value role model has to a flat
     * club billing.
     *
     * An act billed twice would produce two `event_artist` rows for one (event, artist) pair and
     * hit the unique constraint, failing the whole import, so the first billing wins.
     */
    private fun parseArtists(lineup: String?): List<ScrapedArtist> {
        if (lineup == null) return emptyList()
        return splitSupportActs(lineup.replace(WITH_SEPARATOR, ", "))
            .flatMap { it.split(B2B_SEPARATOR) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { act -> ScrapedArtist(name = cleanActName(act), role = roleOf(act)) }
            .filter { it.name.isNotBlank() && !isNonArtistName(it.name) && !isProgrammeFiller(it.name) }
            .distinctBy { it.name.lowercase() }
    }

    /** Strips the host label and the shared act suffixes (`(live)`, tour tails) from an act name. */
    private fun cleanActName(act: String): String =
        stripArtistSuffix(
            act
                .replaceFirst(HOST_LABEL, "")
                .replace(HOST_ANNOTATION, "")
                .trim()
        )

    /** `HEADLINER` for an act marked live or hosting, `DJ` for the rest of the billing. */
    private fun roleOf(act: String): String =
        if (LIVE_MARKER.containsMatchIn(act) || HOST_LABEL.containsMatchIn(act) || HOST_ANNOTATION.containsMatchIn(act)) {
            "HEADLINER"
        } else {
            "DJ"
        }

    /** True when an act name is really one of the venue's lineup fillers — see [PROGRAMME_FILLER]. */
    private fun isProgrammeFiller(name: String): Boolean = PROGRAMME_FILLER.matches(name.trim().replace(WHITESPACE, " "))

    private companion object {
        /** The item attribute holding the full date; the rendered calendar column omits the year. */
        const val DATE_ATTRIBUTE = "data-date"

        /** The detail-page path prefix the Webflow slug follows. */
        const val EVENT_PATH = "/events/"

        /** The title the venue gives a closed day, a programme entry but not an event. */
        const val CLOSED_MARKER = "CLOSED"

        /** The `data-date` rendering, e.g. `August 7, 2026`. */
        val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMMM d, yyyy")
                .toFormatter(Locale.ENGLISH)

        /** The start-time rendering, e.g. `10:00 pm` — lowercase meridiem, hence case-insensitive. */
        val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("h:mm a")
                .toFormatter(Locale.ENGLISH)

        /** The `w/` ("with") marker introducing the acts a host plays with — an act boundary. */
        val WITH_SEPARATOR = Regex("""\s+w/\s*""", RegexOption.IGNORE_CASE)

        /** The "hosted by …" lineup lead-in — a role, not part of the name. */
        val HOST_LABEL = Regex("""^hosted\s+by\s+""", RegexOption.IGNORE_CASE)

        /** The trailing `(Host)` annotation on the act fronting a drag night. */
        val HOST_ANNOTATION = Regex("""\s*\(\s*host\s*\)\s*$""", RegexOption.IGNORE_CASE)

        /** The live-act marker, written `(Live)` or as a trailing shouted `LIVE`. */
        val LIVE_MARKER = Regex("""\(\s*live\s*\)\s*$|\blive\s*$""", RegexOption.IGNORE_CASE)

        /**
         * What the venue writes in the lineup field with no billing to state: activities rather than
         * performers ("Ping Pong, Music And Hangout" for the open-decks nights), an open slot ("open
         * decks slot"), or a lineup withheld ("Secret Line-Up", "Spontaneous :)"). The shared
         * [isNonArtistName] denylist covers only the bare `TBA`/`more tba` tokens the venue also uses.
         *
         * Fully anchored on the whitespace-collapsed value, so a real act whose name merely contains
         * one of these words is untouched. Curated and reactive, like every such list in the
         * scrapers: entries are added as the venue's phrasings surface.
         */
        val PROGRAMME_FILLER =
            Regex(
                """secret\s+line\s*-?\s*up|line\s*-?\s*up|spontaneous\b.*|open\s+decks(\s+slot)?|ping\s+pong|music|hangouts?""",
                RegexOption.IGNORE_CASE
            )
    }
}

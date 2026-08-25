package de.norm.events.scraper.crackbellmer

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
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
 * Every night is an `.event-item` in one Finsweet CMS list, carrying a `data-date`, an `h:mm a`
 * start time, the title, a comma-separated genre line, a comma-separated lineup line, and a poster.
 * The `/events/<slug>` page it links to adds only a prose blurb, which
 * [CrackBellmerDetailPageScraper] reads.
 *
 * 1. **`data-date` is the only place the year is written.** The rendered calendar column spells the
 *    date as `Fri . 7 . 8 .` while the attribute carries the full `August 7, 2026` — and ADR-007
 *    ranks a `data-*` attribute above a class name anyway.
 * 2. **The list is the venue's whole published programme, not a month.** The `previous-month`,
 *    `this-month` and `next-month` tabs serve identical markup and filter it client-side, so the
 *    listing carries about a month of already-passed nights. Those are dropped here, before the
 *    importer's detail fetch, so no HTTP is wasted on events persistence would discard
 *    ([dropPastEvents]).
 * 3. **The venue states no event category.** Its genre line ("Techno, House", but also "Drag Show",
 *    "Concert meets Pub Quiz") is the only cue, so the type is read from the title and then the
 *    genre with the shared keyword classifier, defaulting to `PARTY` — this is a dance bar whose
 *    programme is DJ nights, so a cue-less night is one of those.
 * 4. **A poster-less night still renders an `<img>`**, pointing at Webflow's placeholder SVG and
 *    flagged `w-dyn-bind-empty`; the same flag marks an empty genre or lineup paragraph.
 *
 * @see CRACK_BELLMER_LIMITATIONS for what the venue does not publish.
 * @see CrackBellmerWebsiteImporter for the HTTP fetch orchestrator.
 */
class CrackBellmerOverviewPageScraper(
    /** Clock for the past-event cutoff. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event items from the programme document.
     *
     * @param sourceUrl the URL the document was fetched from, used to resolve the relative
     *   `/events/<slug>` detail links.
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

        return dropPastEvents(events)
    }

    /** Parses one `.event-item` into a [ScrapedEvent], or `null` when it is unusable or not an event. */
    @Suppress("ReturnCount") // Guard clauses for the required link/title/date and the closed-day marker are clearer than nesting
    private fun parseItem(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val href = item.attrAt("a[href*=/events/]", "href") ?: error("No event link found")
        val title = item.textAt("[fs-list-field=title]")?.let(::cleanEventTitle) ?: error("No title found")
        // The venue publishes its closed days as programme entries. They name no event, so storing
        // one would put a night titled "CLOSED" in the calendar.
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

    /** Reads the item's `data-date` (`August 7, 2026`), the only rendering that carries the year. */
    private fun parseDate(item: Element): LocalDate? {
        val text = item.attr(DATE_ATTRIBUTE).takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDate.parse(text.trim(), DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Keeps only events dated today or later, dropping the recently-passed ones the listing carries.
     *
     * Same-day events are kept — the night may still be happening — matching the persistence layer's
     * cutoff (`EventUpsertService`), which enforces the same rule regardless. Applying it here spares
     * the importer a detail-page fetch per past event, which is roughly half the listing; it is an
     * optimization, not the source of truth.
     */
    private fun dropPastEvents(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val today = LocalDate.now(clock)
        val (upcoming, past) = events.partition { !it.eventDate.isBefore(today) }
        if (past.isNotEmpty()) {
            logger.info { "Dropped ${past.size} past event(s) from the Crack Bellmer listing" }
        }
        return upcoming
    }

    /**
     * Types an event from its [title] and then its [genre], defaulting to `PARTY`.
     *
     * The venue emits no category at all, so both classifications go through the shared
     * [inferUnmarkedTitleType] keyword classifier — including on the genre line, which is where this
     * venue tends to name a non-musical format ("Concert meets Pub Quiz" → `QUIZ`) while the title
     * stays a bare event name. The fallback is deliberately `PARTY` rather than
     * [inferConcertVenueType][de.norm.events.scraper.inferConcertVenueType]'s `CONCERT` or
     * [inferUnmarkedTitleType]'s `OTHER`: Crack Bellmer is a dance bar programming DJ nights, so a
     * night with no format cue is one of those, not a gig and not an unknown.
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
     * Reads the night's acts from its lineup line.
     *
     * The venue writes a flat, comma-separated billing, so the shared [splitSupportActs] applies
     * directly — with two venue spellings normalised first: `w/` introduces the acts a host plays
     * with ("hosted by Nicole M Pikole w/ KumKween & Slaxy Lexy"), and `b2b` joins two DJs into one
     * slot, both of which open a new act.
     *
     * Roles come from the venue's own annotations, the only distinction it draws: a `(live)` /
     * trailing `LIVE` marks a band, and `hosted by …` / `(Host)` marks whoever fronts the night —
     * both `HEADLINER`. Everything else is a `DJ` booking, the closest the three-value role model has
     * to a flat club billing.
     *
     * An act billed twice on one night would produce two `event_artist` rows for the same
     * (event, artist) pair and hit that table's unique constraint, failing the whole import, so the
     * first billing wins.
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

    /** Strips the venue's host label and the shared act suffixes (`(live)`, tour tails) from an act name. */
    private fun cleanActName(act: String): String =
        stripArtistSuffix(
            act
                .replaceFirst(HOST_LABEL, "")
                .replace(HOST_ANNOTATION, "")
                .trim()
        )

    /** `HEADLINER` for an act the venue marked as playing live or hosting, `DJ` for the rest of the billing. */
    private fun roleOf(act: String): String =
        if (LIVE_MARKER.containsMatchIn(act) || HOST_LABEL.containsMatchIn(act) || HOST_ANNOTATION.containsMatchIn(act)) {
            "HEADLINER"
        } else {
            "DJ"
        }

    /** True when an act name is really one of the venue's own lineup fillers — see [PROGRAMME_FILLER]. */
    private fun isProgrammeFiller(name: String): Boolean = PROGRAMME_FILLER.matches(name.trim().replace(WHITESPACE, " "))

    private companion object {
        /** The item attribute holding the full date; the rendered calendar column omits the year. */
        const val DATE_ATTRIBUTE = "data-date"

        /** The detail-page path prefix the Webflow slug follows. */
        const val EVENT_PATH = "/events/"

        /** The title the venue gives a closed day, which is a programme entry but not an event. */
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

        /** The back-to-back marker joining two DJs into one slot. */
        val B2B_SEPARATOR = Regex("""\s+b2b\s+""", RegexOption.IGNORE_CASE)

        /** The venue's "hosted by …" lineup lead-in — a role, not part of the name. */
        val HOST_LABEL = Regex("""^hosted\s+by\s+""", RegexOption.IGNORE_CASE)

        /** The venue's trailing `(Host)` annotation on the act fronting a drag night. */
        val HOST_ANNOTATION = Regex("""\s*\(\s*host\s*\)\s*$""", RegexOption.IGNORE_CASE)

        /** The venue's live-act marker, written either `(Live)` or as a trailing shouted `LIVE`. */
        val LIVE_MARKER = Regex("""\(\s*live\s*\)\s*$|\blive\s*$""", RegexOption.IGNORE_CASE)

        /**
         * What the venue writes in the lineup field when there is no billing to state: the night's
         * activities rather than performers ("Ping Pong, Music And Hangout" for the open-decks
         * nights), an open slot ("open decks slot"), or a lineup deliberately withheld ("Secret
         * Line-Up", "Spontaneous :)"). The shared [isNonArtistName] denylist covers only the bare
         * `TBA`/`more tba` tokens the venue also uses.
         *
         * Matching is fully anchored on the whitespace-collapsed value, so a real act whose name
         * merely contains one of these words is untouched. Curated and reactive, like every other
         * such list in the scrapers: entries are added as the venue's phrasings surface.
         */
        val PROGRAMME_FILLER =
            Regex(
                """secret\s+line\s*-?\s*up|line\s*-?\s*up|spontaneous\b.*|open\s+decks(\s+slot)?|ping\s+pong|music|hangouts?""",
                RegexOption.IGNORE_CASE
            )

        /** A run of whitespace inside an act name, collapsed before the anchored filler match. */
        val WHITESPACE = Regex("""\s+""")
    }
}

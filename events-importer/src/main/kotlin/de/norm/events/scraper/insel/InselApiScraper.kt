package de.norm.events.scraper.insel

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Time zone the venue publishes in — its offset-stamped `time` instants are converted to this wall clock. */
internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Pure parser for Kulturhaus Insel Berlin's programme, sourced from a Gatsby **static-query**
 * artefact backed by DatoCMS.
 *
 * The homepage *is* the programme, but unlike Zenner its events are not in its own `page-data.json`
 * — they come from a shared static query under `/page-data/sq/d/<queryHash>.json`. The hash is not
 * guessable, so [InselWebsiteImporter] reads the candidates from the page's `staticQueryHashes` and
 * hands each artefact here; [scrape] returns `null` for one that is not the events artefact.
 *
 * Each node carries a `name`, an offset-stamped `time`, a `wholeDay` flag, an HTML `description` and
 * a DatoCMS image. Three properties of that payload shape the parsing:
 *  1. **The artefact holds the venue's whole archive** — hundreds of past events for a few dozen
 *     upcoming — so past dates are dropped here rather than minting throwaways on every run.
 *  2. **The CMS `eventType` field is not a category.** It once held one ("Konzert"); every current
 *     event has the event's own name copied into it, so it is read only for the closed-function
 *     marker ([isClosedFunction]) and never mapped to an [EventType].
 *  3. **`time` is the first time of the evening, not reliably the start** — the *doors* time where
 *     the description bills both. So `Einlass:` / `Beginn:` win, and `time` supplies the start only
 *     when neither is written ([parseTimes]).
 *
 * **The description is the venue's real data sheet**, mined for four things the JSON has no field
 * for: those times, the promoter, the support billing and free entry. Its ticket link is identified
 * by **anchor text** rather than host — the venue writes `>> TICKETS GIBT ES HIER <<` — which keeps
 * the YouTube and Bandcamp links it also embeds out of [ScrapedEvent.ticketUrl]. Those lines are then
 * dropped from the stored description ([METADATA_LINE_PATTERNS]), both to avoid duplicating the
 * fields and because the date among them is sometimes months off the real one — which is why the
 * date is only ever taken from `time`.
 *
 * **The title is trusted as the act**: a concert house whose titles are usually just the artist's
 * name, so the type defaults to `CONCERT` and the title is minted as the headliner. Two frames are
 * unpacked first so an event *name* is not stored as a performer — `… w/ <acts>` yields the acts
 * after the marker, a `•`-separated title its first segment. A trailing origin tag ("pinkpool (Bln)")
 * is stripped from the act ([ORIGIN_TAG_PATTERN]): it is provenance, and leaving it on would stop the
 * act resolving onto another venue's booking of the same artist, though the stored *title* keeps the
 * venue's spelling. A closed private function is imported so the calendar shows the venue shut, but
 * typed `OTHER` with no artists. A sold-out show is marked only in prose — a `!!SOLD OUT!!` prefix or
 * an "AUSVERKAUFT" line — and the prefix is stripped so it stays out of the `sourceId` and the row
 * survives the venue removing it.
 *
 * The venue writes "+ support pinkpool" beside "Support: Alles Karo", so only a colon or a
 * line-leading `support` marker separates a billing from prose reliably. A handful of titles are
 * event names the `CONCERT` default then mints as artists — a club night, a themed programme, a city
 * tail the suffix rules miss. None carries a structural cue distinguishing it from the titles that
 * really are act names, so the default is kept rather than suppressing the whole programme's lineup.
 *
 * @see INSEL_LIMITATIONS for what the venue does not publish.
 * @see InselWebsiteImporter for the HTTP fetch orchestrator and the artefact discovery.
 * @see <a href="https://www.inselberlin.de/">Kulturhaus Insel Berlin</a>
 */
@Suppress("LongComment") // The venue's prose is its data sheet, and this block is how the parser mines it.
class InselApiScraper(
    /** Clock for the past-event cutoff. Defaults to the venue's own time zone; override in tests for determinism. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    // Unknown fields are ignored (Jackson 3 default), so the payload's presentation-only extras
    // (srcSets, aspect ratios, galleries) deserialize away silently.
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses every upcoming event from a Gatsby static-query artefact.
     *
     * @param json the raw JSON body of one `/page-data/sq/d/<hash>.json` artefact.
     * @param sourceUrl the venue's programme page, stored on every event — Insel has no per-event
     *   pages, so this is the canonical link back to the source.
     * @return upcoming [ScrapedEvent]s (today onward) in listing order; an empty list when this
     *   *is* the events artefact but nothing is upcoming; and **`null` when it is a different
     *   static query altogether**, so the caller knows to try the next candidate.
     */
    @Suppress("ReturnCount") // Guard clauses for the unparseable body and the wrong artefact are clearer than nesting.
    fun scrape(
        json: String,
        sourceUrl: String
    ): List<ScrapedEvent>? {
        val nodes = eventNodes(json) ?: return null
        logger.info { "Found ${nodes.size()} event(s) in the Insel static-query artefact" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        val events =
            nodes.mapNotNull { edge ->
                try {
                    parseEvent(jsonMapper.treeToValue(edge.path("node"), InselEventNode::class.java), sourceUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Insel event, skipping" }
                    null
                }
            }

        return dropPastEvents(events)
    }

    /**
     * The artefact's `data.allDatoCmsEvent.edges` array, or `null` when this is a different static
     * query.
     *
     * A sibling query publishes the *same* `allDatoCmsEvent` collection projected down to a bare
     * date and category, so the presence of the collection alone is not enough: the array is only
     * accepted once its first node carries a `name`, which is the field this parser is built on.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed payload must degrade to null, never abort the import.
        "ReturnCount" // Guard clauses for the unparseable body and the wrong artefact are clearer than nesting.
    )
    private fun eventNodes(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse an Insel static-query artefact" }
                return null
            }
        val edges = root.path("data").path(EVENTS_QUERY).path("edges")
        if (!edges.isArray) return null
        return edges.takeIf { it.isEmpty || it.first().path("node").has("name") }
    }

    @Suppress("ReturnCount") // Guard clauses for the required title and date are clearer than nesting.
    private fun parseEvent(
        node: InselEventNode,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawName = node.name.blankToNull()
        if (rawName == null) {
            logger.warn { "Insel event at '${node.time}' has no name, skipping" }
            return null
        }
        val title = cleanEventTitle(rawName.replace(SOLD_OUT_PREFIX, ""))

        val start = parseInstant(node.time)
        if (start == null) {
            logger.warn { "Insel event '$title' has no parseable time '${node.time}', skipping" }
            return null
        }

        val lines = descriptionLines(node.description)
        val supportNames = supportActs(lines)
        val eventType = resolveEventType(title, node.eventType?.eventType)
        val eventDate = start.toLocalDate()
        val (doors, startTime) = parseTimes(lines, start.toLocalTime(), node.wholeDay)

        return ScrapedEvent(
            title = title,
            subtitle = supportLine(lines),
            description = prose(lines, title),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doors,
            startTime = startTime,
            imageUrl =
                node.image
                    ?.fluid
                    ?.src
                    .blankToNull()
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.INSEL.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketUrl(node.description),
            free = lines.any { FREE_ENTRY_PATTERN.containsMatchIn(it) },
            soldOut = SOLD_OUT_PREFIX.containsMatchIn(rawName) || lines.any { SOLD_OUT_LINE_PATTERN.containsMatchIn(it) },
            promoters = listOfNotNull(promoter(lines)),
            artists = buildArtists(title, supportNames, eventType)
        )
    }

    /**
     * The event's doors and start times.
     *
     * The description's own labels win: `Einlass` is the doors time and `Beginn` the start, in
     * either the `19.00 Uhr` or the `19 Uhr` spelling the venue alternates between. When it bills
     * neither, the node's [fallbackStart] — the wall-clock time of the `time` instant — is the
     * start. An all-day entry has no meaningful time at all and gets none.
     */
    private fun parseTimes(
        lines: List<String>,
        fallbackStart: LocalTime,
        wholeDay: Boolean
    ): Pair<LocalTime?, LocalTime?> {
        if (wholeDay) return null to null
        val doors = lines.firstNotNullOfOrNull { DOORS_PATTERN.find(it)?.let(::toLocalTime) }
        val start = lines.firstNotNullOfOrNull { START_PATTERN.find(it)?.let(::toLocalTime) }
        return doors to (start ?: fallbackStart.takeIf { doors == null })
    }

    /**
     * The event's lineup, keyed off its resolved type.
     *
     * A concert's title is the act (see the class KDoc's title frames); everything else — a
     * closed function, a poetry slam — bills no performer this parser can trust, so it gets none.
     */
    private fun buildArtists(
        title: String,
        supportNames: List<String>,
        eventType: String
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name) return emptyList()
        val supportActs =
            supportNames
                .map(::stripOriginTag)
                .filterNot { isNonArtistName(it) }
                .map { ScrapedArtist(name = it, role = "SUPPORT") }
        return headliners(title) + supportActs
    }

    /**
     * The headliners billed by a concert title, after unpacking the `•`-separated title frame
     * whose first segment is the act. Origin tags are stripped and non-artists dropped.
     *
     * The venue's other frame — a `… w/ <acts>` guest billing — used to be unpacked here too.
     * The rule now lives in the shared [headlinersFromTitle] and is requested with
     * `unpackWithFrame`; it is opt-in because `w/` joins collaborators at some venues rather than
     * framing a guest (see that parameter's KDoc). Insel is unambiguous — its `w/` titles always
     * name the night first — so it asks for the unpacking. The bullet split stays local and runs
     * first: `w/` never appears after a bullet in this feed.
     */
    private fun headliners(title: String): List<ScrapedArtist> =
        headlinersFromTitle(title.substringBefore(BULLET_SEPARATOR).trim(), unpackWithFrame = true)
            .map { it.copy(name = stripOriginTag(it.name)) }
            .filterNot { isNonArtistName(it.name) }
            .distinctBy { it.name.lowercase() }

    /** The event type — `CONCERT` for this concert house, unless the entry is a closed private function. */
    private fun resolveEventType(
        title: String,
        cmsType: String?
    ): String = if (isClosedFunction(title, cmsType)) EventType.OTHER.name else inferConcertVenueType(title)

    private fun dropPastEvents(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val today = LocalDate.now(clock)
        val (upcoming, past) = events.partition { !it.eventDate.isBefore(today) }
        if (past.isNotEmpty()) {
            logger.info { "Dropped ${past.size} past event(s) from the Insel archive" }
        }
        return upcoming
    }

    /** Converts the node's offset-stamped `time` to the venue's own [BERLIN] wall clock, or null when unparseable. */
    private fun parseInstant(raw: String?): ZonedDateTime? {
        val value = raw.blankToNull() ?: return null
        @Suppress("SwallowedException") // The unparseable value is reported by the caller, which knows the event name.
        return try {
            OffsetDateTime.parse(value).atZoneSameInstant(BERLIN)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private companion object {
        /** The DatoCMS GraphQL collection holding the programme. */
        const val EVENTS_QUERY = "allDatoCmsEvent"

        /** The `•` the venue separates a title's act, city and format with. */
        const val BULLET_SEPARATOR = '•'
    }
}

/** Lines longer than this are prose, not a one-line billing or metadata label. */
private const val MAX_METADATA_LINE = 80

/** How far into the description a promoter credit is looked for; the venue puts it first or just after the date. */
private const val PROMOTER_LINE_LIMIT = 3

/** Trims this string and returns `null` when it is null, empty, or all whitespace. */
private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * The description's prose, with the lines whose contents are stored in dedicated fields removed
 * — including the bare act line, which most announcements repeat verbatim from the title.
 */
private fun prose(
    lines: List<String>,
    title: String
): String? =
    lines
        .filterNot { line -> line.length <= MAX_METADATA_LINE && METADATA_LINE_PATTERNS.any { it.containsMatchIn(line) } }
        .filterNot { line -> stripOriginTag(line).equals(stripOriginTag(title), ignoreCase = true) }
        .joinToString("\n")
        .trim()
        .takeIf { it.isNotBlank() }

/** The support-billing line, kept verbatim as the event's subtitle. */
private fun supportLine(lines: List<String>): String? = lines.firstOrNull { it.length <= MAX_METADATA_LINE && SUPPORT_PATTERN.containsMatchIn(it) }?.trim()

/** The acts billed after a `Support:` / `+ support` marker, in listing order. */
private fun supportActs(lines: List<String>): List<String> =
    supportLine(lines)
        ?.let { SUPPORT_PATTERN.find(it)?.groupValues?.get(1) }
        ?.let { tail -> tail.split(SUPPORT_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() } }
        .orEmpty()

/** The promoter behind an `ATOK prs.` / `… präsentiert:` credit, from the description's opening lines. */
private fun promoter(lines: List<String>): String? =
    lines
        .take(PROMOTER_LINE_LIMIT)
        .firstNotNullOfOrNull { PROMOTER_PATTERN.find(it)?.groupValues?.get(1) }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Splits an event's HTML description into trimmed, non-blank text lines.
 *
 * The CMS stores whatever the venue pasted in — Facebook's `<div class="xdj266r …">` soup for some
 * events, plain `<p>`/`<br>` for others — so the block and break tags are turned into line breaks
 * before the tags are dropped. Working line by line is what lets a one-line billing ("Einlass 19.00
 * Uhr") be told apart from the prose around it.
 */
private fun descriptionLines(html: String?): List<String> {
    val source = html.blankToNull() ?: return emptyList()
    return Jsoup
        .parse(source.replace(BLOCK_END_PATTERN, "\n"))
        .wholeText()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

/** The tags that end a line in the venue's pasted HTML: an explicit break, or the close of any block element. */
private val BLOCK_END_PATTERN = Regex("""<br\s*/?>|</(?:p|div|h[1-6]|li)>""", RegexOption.IGNORE_CASE)

/** The `!!SOLD OUT!!` prefix the venue puts in front of a sold-out show's name. */
private val SOLD_OUT_PREFIX = Regex("""^\s*!*\s*sold\s*out\s*!*\s*""", RegexOption.IGNORE_CASE)

/** The venue's German sold-out notice, written as its own description line. */
private val SOLD_OUT_LINE_PATTERN = Regex("""\bausverkauft\b""", RegexOption.IGNORE_CASE)

/** The venue's free-entry notice. */
private val FREE_ENTRY_PATTERN = Regex("""\beintritt\s+frei\b|\bfree\s+entry\b""", RegexOption.IGNORE_CASE)

/** `Einlass 19.00 Uhr` / `Einlass: 19 Uhr` — the doors time; the minutes are optional. */
private val DOORS_PATTERN = Regex("""Einlass\s*:?\s*(\d{1,2})(?:[.,:](\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

/** `Beginn 20.00 Uhr` / `Beginn: 20 Uhr` — the start time; the venue also mistypes the separator as a comma. */
private val START_PATTERN = Regex("""Beginn\s*:?\s*(\d{1,2})(?:[.,:](\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

/** Builds the [LocalTime] a [DOORS_PATTERN] / [START_PATTERN] match names, or null when the hour is out of range. */
private fun toLocalTime(match: MatchResult): LocalTime? =
    runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].ifBlank { "0" }.toInt()) }.getOrNull()

/**
 * The support billing and the acts after it — `Support: Alles Karo`, `+ support: Karwendel`, and the
 * run-together `Marlin BeachSupport: Mellow Ma`. The colon is required: the venue also writes
 * "+ support pinkpool" without one, but a bare `support` mid-prose is far too common to key on.
 */
private val SUPPORT_PATTERN = Regex("""\+?\s*supports?\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

/** Separators inside a support billing. */
private val SUPPORT_SEPARATOR = Regex("""\s*[,+&]\s*|\s+und\s+""", RegexOption.IGNORE_CASE)

/**
 * The promoter credit opening a description — `ATOK prs.`, `All Rooms prs.`, `Kunst&Krawall prs.`,
 * `Das forgotten female* composers e.V. präsentiert:`. The name is captured non-greedily and may not
 * span a line, so a run-together `Kulturalarm prs.Sameen Qasim` still yields just the promoter.
 */
private val PROMOTER_PATTERN =
    Regex("""^(.{2,60}?)\s*(?:prs\.|pres\.|präsentiert)\s*:?(?:\s|$)""", RegexOption.IGNORE_CASE)

/**
 * A trailing provenance tag on an act name — a two-or-three-letter country code or the venue's
 * `(Bln)` shorthand for Berlin. It marks where an act is from, not what it is called, so leaving it
 * on would keep "Internal Bleeding (US)" from resolving to the same artist row as another venue's
 * "Internal Bleeding". Anchored to the end and limited to short, letter-only tags, so a
 * parenthesised alias ("Sickboyrari (Black Kray)") survives.
 */
private val ORIGIN_TAG_PATTERN = Regex("""\s*\(\s*(?:\p{L}{2,3}|Bln)\s*\)\s*$""", RegexOption.IGNORE_CASE)

/** Strips a trailing [ORIGIN_TAG_PATTERN], keeping the input when stripping would leave nothing. */
private fun stripOriginTag(name: String): String {
    val stripped = name.replace(ORIGIN_TAG_PATTERN, "").trim()
    return stripped.ifBlank { name.trim() }
}

/** The German phrases the venue uses for a day booked as a private function, when its garden is closed to the public. */
private val CLOSED_FUNCTION_PATTERN =
    Regex("""geschlossene\s+(?:gesellschaft|veranstaltung)|firmen-?event""", RegexOption.IGNORE_CASE)

/**
 * True when this entry marks a day the venue is closed for a private function rather than a public
 * event. Checked against both the title and the CMS `eventType` field, because the venue writes the
 * marker in one or the other.
 */
private fun isClosedFunction(
    title: String,
    cmsType: String?
): Boolean = CLOSED_FUNCTION_PATTERN.containsMatchIn(title) || cmsType?.let { CLOSED_FUNCTION_PATTERN.containsMatchIn(it) } == true

/**
 * Description lines whose content is stored in a dedicated field and would only be duplicated in the
 * description: the promoter credit, the doors and start times, the free-entry notice, the support
 * billing, the ticket call to action, and the German weekday-and-date line — which is not merely
 * redundant but occasionally *stale*, printing a date months away from the event's real one.
 */
private val METADATA_LINE_PATTERNS =
    listOf(
        PROMOTER_PATTERN,
        DOORS_PATTERN,
        START_PATTERN,
        FREE_ENTRY_PATTERN,
        SUPPORT_PATTERN,
        Regex("""\bticket""", RegexOption.IGNORE_CASE),
        Regex("""^(?:Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonntag)\s+\d""", RegexOption.IGNORE_CASE)
    )

/**
 * The ticket-shop link in an event's description, identified by its **anchor text** rather than its
 * host: the venue writes `>> TICKETS GIBT ES HIER <<` or `🎟️ TICKETS IM VORVERKAUF 🎟️`, and sells
 * through a different shop nearly every time (Eventim, DICE, Eventbrite, Tickettailor, rausgegangen,
 * a record shop). The YouTube and Bandcamp links it embeds beside them render as bare URLs and carry
 * no such text, so they are never mistaken for a ticket link.
 */
private fun ticketUrl(html: String?): String? {
    val source = html.blankToNull() ?: return null
    return Jsoup
        .parse(source)
        .select("a[href]")
        .firstOrNull { it.text().contains("ticket", ignoreCase = true) }
        ?.attr("href")
        ?.trim()
        ?.takeIf { it.startsWith("http") }
}

/**
 * One event in the `allDatoCmsEvent.edges[].node` array, mapped from its JSON by Jackson.
 *
 * Only the fields the venue populates are declared; unknown keys (galleries, cover-image crops,
 * responsive srcSets) are ignored. Every field is nullable so a partial or evolving payload
 * deserializes cleanly and is validated in [InselApiScraper] instead.
 */
private data class InselEventNode(
    val name: String? = null,
    /** ISO 8601 datetime with the venue's own UTC offset, e.g. `2026-08-09T16:00:00+02:00`. */
    val time: String? = null,
    /** True for an all-day entry, which carries no meaningful clock time. */
    val wholeDay: Boolean = false,
    /** HTML blurb pasted from the venue's own announcement; the data sheet this parser mines. */
    val description: String? = null,
    /** Once the venue's category field, now filled with the event's own name; read only for the closed-function marker. */
    val eventType: InselEventTypeNode? = null,
    val image: InselImage? = null
)

private data class InselEventTypeNode(
    val eventType: String? = null
)

/** The DatoCMS image reference; only the CDN [InselImageFluid.src] is used. */
private data class InselImage(
    val fluid: InselImageFluid? = null
)

private data class InselImageFluid(
    /** Absolute DatoCMS CDN URL of the poster image. */
    val src: String? = null
)

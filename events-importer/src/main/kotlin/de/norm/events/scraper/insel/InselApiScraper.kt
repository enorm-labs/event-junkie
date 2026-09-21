package de.norm.events.scraper.insel

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dropPastEvents
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
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Pure parser for Kulturhaus Insel Berlin's programme from a Gatsby static-query artefact
 * backed by DatoCMS. Unlike Zenner the events are not in the page's own `page-data.json` but
 * under `/page-data/sq/d/<queryHash>.json`; the hash is not guessable, so [InselWebsiteImporter]
 * reads the candidates from the page's `staticQueryHashes` and hands each here, and [scrape]
 * returns `null` for one that is not the events artefact.
 *
 * Each node carries a `name`, an offset-stamped `time`, a `wholeDay` flag, an HTML `description`
 * and a DatoCMS image. Three properties shape the parsing: the artefact holds the whole archive,
 * so past dates are dropped here; the CMS `eventType` field is not a category (every current
 * event has its own name copied into it), so it is read only for the closed-function marker
 * ([isClosedFunction]); and `time` is the first time of the evening, the doors time where the
 * description bills both, so `Einlass:` / `Beginn:` win and `time` supplies the start only when
 * neither is written ([parseTimes]).
 *
 * The description is the venue's data sheet, mined for those times, the promoter, the support
 * billing and free entry. Its ticket link is identified by anchor text (`>> TICKETS GIBT ES HIER
 * <<`) rather than host, keeping the embedded YouTube and Bandcamp links out of
 * [ScrapedEvent.ticketUrl]. Those lines are dropped from the stored description
 * ([METADATA_LINE_PATTERNS]), also because the date among them is sometimes months off, which
 * is why the date is only taken from `time`.
 *
 * The title is trusted as the act: a concert house whose titles are usually the artist's name,
 * so `CONCERT` by default and the title minted as headliner. Two frames are unpacked first: `…
 * w/ <acts>` yields the acts after the marker, a `•`-separated title its first segment. A
 * trailing origin tag ("pinkpool (Bln)") is stripped from the act ([ORIGIN_TAG_PATTERN]) so it
 * resolves onto another venue's booking; the stored title keeps the venue's spelling. A closed
 * private function is imported as `OTHER` with no artists, so the calendar shows the venue shut.
 * Sold out is prose only, a `!!SOLD OUT!!` prefix or an "AUSVERKAUFT" line, and the prefix is
 * stripped so it stays out of the `sourceId`.
 *
 * The venue writes "+ support pinkpool" beside "Support: Alles Karo", so only a colon or a
 * line-leading `support` separates a billing from prose. A handful of titles are event names the
 * `CONCERT` default mints as artists (a club night, a themed programme, a city tail); none
 * carries a structural cue, so the default is kept.
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

    // Unknown fields are ignored, so srcSets, aspect ratios and galleries deserialize away.
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses every upcoming event from a static-query artefact.
     *
     * @param json the raw body of one `/page-data/sq/d/<hash>.json` artefact.
     * @param sourceUrl the programme page, stored on every event; Insel has no per-event pages.
     * @return upcoming [ScrapedEvent]s in listing order; empty when this is the events artefact
     * with nothing upcoming; `null` when it is a different static query, so the caller tries the
     * next candidate.
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

        return events.dropPastEvents(clock) { dropped ->
            logger.info { "Dropped $dropped past event(s) from the Insel archive" }
        }
    }

    /**
     * The artefact's `data.allDatoCmsEvent.edges`, or `null` when this is a different query. A
     * sibling query publishes the same collection projected to a bare date and category, so the
     * array is accepted only once its first node carries a `name`.
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
     * Doors and start: the description's `Einlass` and `Beginn` win, in the `19.00 Uhr` or `19 Uhr`
     * spelling; otherwise [fallbackStart], the wall-clock time of `time`, is the start. An all-day
     * entry gets none.
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
     * The lineup by resolved type: a concert's title is the act; a closed function or a poetry slam
     * bills no performer this parser can trust.
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
     * The headliners of a concert title, after unpacking the `•`-separated frame whose first segment
     * is the act; origin tags stripped, non-artists dropped. The `… w/ <acts>` frame now lives in the
     * shared [headlinersFromTitle] and is requested with `unpackWithFrame`, opt-in because `w/` joins
     * collaborators at some venues; Insel's `w/` titles always name the night first. The bullet split
     * runs first: `w/` never appears after a bullet in this feed.
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

/**
 * The description's prose minus the lines stored in dedicated fields, including the bare act line
 * most announcements repeat from the title.
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
 * Splits an HTML description into trimmed, non-blank lines. The CMS holds whatever the venue
 * pasted, Facebook's `<div class="xdj266r …">` soup or plain `<p>`/`<br>`, so block and break tags
 * become line breaks first. Line by line is what tells "Einlass 19.00 Uhr" from the prose.
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
 * The support billing and the acts after it: `Support: Alles Karo`, `+ support: Karwendel`, the
 * run-together `Marlin BeachSupport: Mellow Ma`. The colon is required: a bare `support`
 * mid-prose is too common.
 */
private val SUPPORT_PATTERN = Regex("""\+?\s*supports?\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

/** Separators inside a support billing. */
private val SUPPORT_SEPARATOR = Regex("""\s*[,+&]\s*|\s+und\s+""", RegexOption.IGNORE_CASE)

/**
 * The promoter credit opening a description: `ATOK prs.`, `All Rooms prs.`, `Kunst&Krawall
 * prs.`, `Das forgotten female* composers e.V. präsentiert:`. Captured non-greedily within one
 * line, so `Kulturalarm prs.Sameen Qasim` yields just the promoter.
 */
private val PROMOTER_PATTERN =
    Regex("""^(.{2,60}?)\s*(?:prs\.|pres\.|präsentiert)\s*:?(?:\s|$)""", RegexOption.IGNORE_CASE)

/**
 * A trailing provenance tag on an act, a two-or-three-letter country code or the venue's `(Bln)`
 * for Berlin; leaving it on would keep "Internal Bleeding (US)" from resolving to another
 * venue's "Internal Bleeding". Anchored to the end and limited to short letter-only tags, so
 * "Sickboyrari (Black Kray)" survives.
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
 * True when this entry marks a day closed for a private function, checked against both the title
 * and the CMS `eventType` field.
 */
private fun isClosedFunction(
    title: String,
    cmsType: String?
): Boolean = CLOSED_FUNCTION_PATTERN.containsMatchIn(title) || cmsType?.let { CLOSED_FUNCTION_PATTERN.containsMatchIn(it) } == true

/**
 * Description lines stored in a dedicated field: the promoter credit, the doors and start times,
 * the free-entry notice, the support billing, the ticket call to action, and the German
 * weekday-and-date line, which is occasionally stale by months.
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
 * The ticket link, identified by anchor text (`>> TICKETS GIBT ES HIER <<`, `🎟️ TICKETS IM
 * VORVERKAUF 🎟️`) rather than host, since the venue sells through a different shop nearly every
 * time (Eventim, DICE, Eventbrite, Tickettailor, rausgegangen, a record shop). The embedded
 * YouTube and Bandcamp links render as bare URLs.
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
 * One event in `allDatoCmsEvent.edges[].node`, mapped by Jackson; only the populated fields,
 * every one nullable, validated in [InselApiScraper].
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

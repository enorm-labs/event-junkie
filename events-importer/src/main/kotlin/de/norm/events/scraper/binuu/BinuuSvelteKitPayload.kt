package de.norm.events.scraper.binuu

import de.norm.events.event.EventType
import de.norm.events.scraper.HH_MM_LENGTH
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Base URL for Bi Nuu's PocketBase file store; event `image.url` values are stored relative to it. */
internal const val BINUU_IMAGE_BASE_URL = "https://pb.binuu.de/api/files/"

/**
 * Extracts the SvelteKit SSR payload embedded in Bi Nuu pages: every page inlines its route
 * data as a JS object literal inside the `kit.start(...)` `<script>`, `…data:{events:[…]}` on
 * the listing and `…data:{item:{…}}` on a detail page. The most stable source on the site,
 * carrying full ISO dates with the year where the cards show only `Sa 11.07.`. The literal uses
 * unquoted property names, so a lenient Jackson mapper parses it, and braces appear inside
 * string values (blurhash previews like `"KPIi{^Av…"`), so the object boundary is found with a
 * string-aware brace scan.
 */
internal object BinuuSvelteKitPayload {
    private val logger = KotlinLogging.logger {}

    private val lenientJson: JsonMapper =
        JsonMapper
            .builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()

    /**
     * Parses the object literal wrapping the `key: openBracket` property and returns the node at
     * [key], or `null`. Matched whitespace-tolerantly (`key\s*:\s*openBracket`), since the site
     * emits both `events:[` and `events: [`.
     *
     * @param key the property that opens the wrapping object (`"events"` for the listing, `"item"`
     * for a detail page).
     * @param openBracket the bracket opening its value (`'['` / `'{'`), disambiguating the marker.
     */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed/absent payload must degrade to null, never abort the import
        "ReturnCount" // Guard clauses for the missing script and unparseable payload are clearer than nesting
    )
    fun dataNode(
        document: Document,
        key: String,
        openBracket: Char
    ): JsonNode? {
        val marker = Regex(Regex.escape(key) + """\s*:\s*""" + Regex.escape(openBracket.toString()))
        val script = document.select("script").map { it.data() }.firstOrNull { marker.containsMatchIn(it) }
        if (script == null) {
            logger.warn { "No Bi Nuu SvelteKit bootstrap script containing '$key' payload found" }
            return null
        }
        val json = extractObjectLiteral(script, marker) ?: return null
        return try {
            lenientJson.readTree(json).get(key)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Bi Nuu SvelteKit '$key' payload" }
            null
        }
    }

    /** Extracts the balanced `{…}` object that opens immediately before [marker]. */
    @Suppress("ReturnCount") // Sequential null-guards for each extraction step are clearer than nesting
    private fun extractObjectLiteral(
        script: String,
        marker: Regex
    ): String? {
        val markerIndex = marker.find(script)?.range?.first ?: return null
        val objectStart = script.lastIndexOf('{', markerIndex)
        if (objectStart < 0) return null
        val objectEnd = matchClosingBrace(script, objectStart)
        if (objectEnd < 0) return null
        return script.substring(objectStart, objectEnd + 1)
    }

    /**
     * The index of the `}` closing the `{` at [start], skipping braces inside double-quoted strings;
     * -1 if never closed.
     */
    private fun matchClosingBrace(
        text: String,
        start: Int
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> {
                        inString = true
                    }

                    '{' -> {
                        depth++
                    }

                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }
}

/** Reads the string array at [field] as a list of trimmed, non-blank values. */
internal fun JsonNode.stringList(field: String): List<String> =
    path(field).mapNotNull { element ->
        element
            .takeUnless { it.isNull }
            ?.asString()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

/**
 * The absolute image URL from the nested `image.url`, prefixing the PocketBase file base for a
 * relative path. `null` without an image.
 */
internal fun JsonNode.binuuImageUrl(): String? {
    val url = path("image").stringOrNull("url") ?: return null
    return if (url.startsWith("http")) url else BINUU_IMAGE_BASE_URL + url
}

/**
 * The date from a timestamp like `"2026-07-19 19:00:00.000Z"`. The `Z` is spurious: the values
 * are Berlin wall-clock times, so only `yyyy-MM-dd` is read with no shift.
 */
internal fun parseBinuuDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank() || raw.length < DATE_LENGTH) return null
    return try {
        LocalDate.parse(raw.trim().substring(0, DATE_LENGTH))
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * The `HH:mm` after the space in `"2026-07-19 19:00:00.000Z"` ([parseBinuuDate] on the spurious
 * `Z`). `null` without a time component.
 */
internal fun parseBinuuTime(raw: String?): LocalTime? {
    val timePart = raw?.trim()?.substringAfter(' ', "")?.take(HH_MM_LENGTH)
    return parseTime(timePart?.takeIf { it.isNotBlank() })
}

/**
 * Maps the single-letter `eventStatus` code: `"r"` (Verlegt, carries `locationNew`), `"p"`
 * (Verschoben, carries the original date in `startOld`). Any other non-blank code is logged and
 * treated as [SCHEDULED][de.norm.events.event.EventStatus.SCHEDULED].
 */
internal fun mapBinuuStatus(code: String?): String {
    val logger = KotlinLogging.logger("de.norm.events.scraper.binuu.BinuuStatus")
    return when (val normalized = code?.trim()?.lowercase()) {
        null, "" -> {
            "SCHEDULED"
        }

        "r" -> {
            "RELOCATED"
        }

        "p" -> {
            "POSTPONED"
        }

        else -> {
            logger.warn { "Unknown Bi Nuu eventStatus code '$normalized', defaulting to SCHEDULED" }
            "SCHEDULED"
        }
    }
}

/**
 * Best-effort [EventType][de.norm.events.event.EventType] from title/subtitle, since Bi Nuu has
 * no category field anywhere. A live-music venue, so `CONCERT` by default; in priority order,
 * `quiz` to `QUIZ`; a known recurring party series ([BINUU_PARTY_SERIES]: GrooveJet, Ultra Night,
 * Boheme Sauvage, which list their own name as title and sole performer, edition number
 * ignored) to `PARTY`; a keyword (`party`, `karaoke`, `dj set`, `club night`, `rave`) to
 * `PARTY`. The description is not sniffed: at this metal/rock-leaning venue `dancefloor`/`disco`
 * show up in tour names (Gutalax's "Shit On The Dancefloor" tour is a death-metal gig). Reactive,
 * consistent with Badehaus's `inferEventType` and `NON_ARTIST_NAMES`.
 */
internal fun inferBinuuEventType(
    title: String,
    subtitle: String?
): String {
    val nameHaystack = "$title ${subtitle.orEmpty()}".lowercase()
    return when {
        "quiz" in nameHaystack -> EventType.QUIZ.name
        isBinuuPartySeries(title) -> EventType.PARTY.name
        PARTY_NAME_KEYWORDS.any { it in nameHaystack } -> EventType.PARTY.name
        else -> EventType.CONCERT.name
    }
}

/**
 * Recurring party series that name themselves as the event and sole performer; lowercase,
 * whitespace-collapsed, edition number stripped. Also on `NON_ARTIST_NAMES`; keep the two in sync.
 */
private val BINUU_PARTY_SERIES = setOf("groovejet berlin", "ultra night", "boheme sauvage")

/** Trailing edition number (`… 5`, `N°141`) ignored when matching [BINUU_PARTY_SERIES]. */
private val BINUU_TRAILING_EDITION = Regex("""\s+(?:n[°º]\s*)?\d+$""", RegexOption.IGNORE_CASE)

private fun isBinuuPartySeries(title: String): Boolean =
    title
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase()
        .replace(BINUU_TRAILING_EDITION, "") in BINUU_PARTY_SERIES

/** Party/DJ-night phrases that, in a title or subtitle, mark a non-concert night. */
private val PARTY_NAME_KEYWORDS = listOf("party", "karaoke", "dj set", "dj-set", "club night", "clubnight", "rave")

private const val DATE_LENGTH = 10

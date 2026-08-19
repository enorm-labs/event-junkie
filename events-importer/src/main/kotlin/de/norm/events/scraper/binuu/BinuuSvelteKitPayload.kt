package de.norm.events.scraper.binuu

import de.norm.events.event.EventType
import de.norm.events.scraper.parseTime
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
 * Extracts the SvelteKit SSR data payload embedded in Bi Nuu pages.
 *
 * Bi Nuu runs on SvelteKit backed by PocketBase. Every page inlines its route
 * data as a JavaScript object literal inside the `kit.start(...)` bootstrap
 * `<script>` — the listing carries `…data:{events:[…]}` and a detail page
 * `…data:{item:{…}}`. This embedded payload is the most stable source on the
 * site: it is structured, machine-readable, survives visual redesigns, and —
 * unlike the rendered DOM — carries full ISO dates *with the year* (the visible
 * cards only show `Sa 11.07.`).
 *
 * The literal uses **unquoted property names**, so it is parsed with a lenient
 * Jackson mapper rather than strict JSON. Braces routinely appear inside string
 * values (blurhash previews like `"KPIi{^Av…"`), so the object boundary is found
 * with a string-aware brace scan, never a naive `}` search.
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
     * Parses the object literal wrapping the `key: openBracket` property from the
     * page's SvelteKit bootstrap script and returns the node at [key], or `null` if
     * it is absent or unparseable.
     *
     * The marker is matched whitespace-tolerantly (`key\s*:\s*openBracket`), since
     * the site emits the literal both minified (`events:[`) and pretty-printed
     * (`events: [`).
     *
     * @param key the property that opens the wrapping object and is returned from it
     *   (`"events"` for the listing, `"item"` for a detail page).
     * @param openBracket the bracket opening that property's value (`'['` / `'{'`),
     *   used to disambiguate the marker from other mentions of the key.
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
     * Returns the index of the `}` closing the `{` at [start], skipping any braces
     * that appear inside double-quoted strings (blurhash previews embed literal `{`).
     * Returns -1 if the object is never closed.
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

/**
 * Reads a trimmed string [field] from this node, or `null` when the field is
 * missing, JSON `null`, or blank.
 */
internal fun JsonNode.stringOrNull(field: String): String? {
    val node = path(field)
    if (node.isMissingNode || node.isNull) return null
    return node.asString().trim().takeIf { it.isNotBlank() }
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
 * Builds the absolute image URL for an event/promoter node from its nested
 * `image.url`, prefixing the PocketBase file base for relative paths and passing
 * already-absolute URLs through unchanged. Returns `null` when no image is present.
 */
internal fun JsonNode.binuuImageUrl(): String? {
    val url = path("image").stringOrNull("url") ?: return null
    return if (url.startsWith("http")) url else BINUU_IMAGE_BASE_URL + url
}

/**
 * Parses the date from a Bi Nuu timestamp like `"2026-07-19 19:00:00.000Z"`.
 *
 * The trailing `Z` is spurious — the values are local Berlin wall-clock times
 * (a 19:00 concert is stored as `19:00…Z`, not converted to UTC), so only the
 * leading `yyyy-MM-dd` is read and no timezone shift is applied. Returns `null`
 * for missing or unparseable input.
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
 * Parses the `HH:mm` time from a Bi Nuu timestamp like `"2026-07-19 19:00:00.000Z"`,
 * reading the wall-clock time after the space (see [parseBinuuDate] on the spurious
 * `Z`). Returns `null` when there is no time component or it is unparseable.
 */
internal fun parseBinuuTime(raw: String?): LocalTime? {
    val timePart = raw?.trim()?.substringAfter(' ', "")?.take(HH_MM_LENGTH)
    return parseTime(timePart?.takeIf { it.isNotBlank() })
}

/**
 * Maps Bi Nuu's single-letter `eventStatus` code to a domain status name.
 *
 * Observed codes: `"r"` (Verlegt / relocated, carries `locationNew`) and `"p"`
 * (Verschoben / postponed, carries the original date in `startOld`). Any other
 * non-blank code is logged and treated as [SCHEDULED][de.norm.events.event.EventStatus.SCHEDULED]
 * so a new code surfaces in the logs rather than being silently mismapped.
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
 * Best-effort inference of an event's [EventType][de.norm.events.event.EventType]
 * from its title/subtitle, since Bi Nuu exposes **no category field anywhere** in
 * the SvelteKit payload — nor anywhere else on the site. Bi Nuu is a live-music venue, so
 * the default is `CONCERT`; only pub quizzes and club/party nights are flipped.
 *
 * Signals, in priority order:
 * 1. `quiz` in the title/subtitle → `QUIZ`.
 * 2. The title is a **known recurring party/DJ series** ([BINUU_PARTY_SERIES]) → `PARTY`.
 *    These series (GrooveJet, Ultra Night, Boheme Sauvage) list *their own name* as the
 *    title and sole performer, so there is no band and no reliable keyword — only the
 *    curated name identifies them. The trailing edition number (`N°141`, `… 5`) is
 *    ignored so every edition matches, mirroring the artist denylist.
 * 3. A party/DJ-night keyword in the title/subtitle (`party`, `karaoke`, `dj set`,
 *    `club night`, `rave`) → `PARTY`.
 *
 * Deliberately does **not** sniff the free-text description for genre words: at this
 * metal/rock-leaning venue, words like `dancefloor`/`disco` show up in band tour and
 * album names (e.g. Gutalax's "Shit On The Dancefloor" tour is a death-metal gig, not
 * a club night), so a description scan mislabels concerts. Like every curated heuristic
 * this is reactive: a newly-seen series is a `CONCERT` until added to [BINUU_PARTY_SERIES].
 * Consistent with Badehaus's `inferEventType` and the artist `NON_ARTIST_NAMES` denylist.
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
 * Recurring Bi Nuu party/DJ series that name themselves as the event and its sole
 * performer. Lowercase, whitespace-collapsed, trailing edition number stripped. These
 * are also on the artist `NON_ARTIST_NAMES` denylist (so the name isn't minted as an
 * act); keep the two in sync when a new series surfaces.
 */
private val BINUU_PARTY_SERIES = setOf("groovejet berlin", "ultra night", "boheme sauvage")

/** Trailing edition number (`… 5`, `N°141`) ignored when matching [BINUU_PARTY_SERIES]. */
private val BINUU_TRAILING_EDITION = Regex("""\s+(?:n[°º]\s*)?\d+$""", RegexOption.IGNORE_CASE)

private fun isBinuuPartySeries(title: String): Boolean =
    title
        .trim()
        .replace(Regex("""\s+"""), " ")
        .lowercase()
        .replace(BINUU_TRAILING_EDITION, "") in BINUU_PARTY_SERIES

/** Party/DJ-night phrases that, in a title or subtitle, mark a non-concert night. */
private val PARTY_NAME_KEYWORDS = listOf("party", "karaoke", "dj set", "dj-set", "club night", "clubnight", "rave")

private const val DATE_LENGTH = 10
private const val HH_MM_LENGTH = 5

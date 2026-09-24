package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Field mapping shared by the Urban Spree scrapers: the venue's category labels and the title
// cleanup both pages need. The venue writes its own name and city into almost every title
// ("Coilguns - Berlin - Urban Spree", "JUD | Urban Spree Berlin", "New Candys (IT Fuzz Club)
// live at Urban Spree Berlin") and prefixes a cancelled show with a marker ("CANCELLED - SOM -
// Berlin - Urban Spree"). Every case is asserted in UrbanSpreeFieldMappingTest.

/**
 * Urban Spree's category labels for [mapEventType][de.norm.events.scraper.mapEventType]: the
 * site pluralises every label ("Concerts", "Exhibitions") and files parts of its programme under
 * its own ("Live Streaming", "Art Fair"). "Workshops" is absent, so it falls through to `null`
 * and the `OTHER` default.
 */
val URBAN_SPREE_CATEGORY_SYNONYMS: Map<String, String> =
    mapOf(
        "concerts" to EventType.CONCERT.name,
        "events" to EventType.OTHER.name,
        "exhibitions" to EventType.EXHIBITION.name,
        "festivals" to EventType.FESTIVAL.name,
        "live streaming" to EventType.SCREENING.name,
        "art fair" to EventType.EXHIBITION.name
    )

/**
 * Percent-encodes the spaces MODX leaves in a media filename: posters are uploaded under their
 * original names ("FLUXO invites EBONY.jpeg"), and Jsoup's `absUrl` resolves the path verbatim,
 * which `URI.create` rejects. `null` for a null or blank input.
 */
fun normalizeAssetUrl(url: String?): String? = url?.takeIf { it.isNotBlank() }?.replace(" ", "%20")

/**
 * A leading status marker, the venue's only cancellation signal ("CANCELLED - SOM - Berlin -
 * Urban Spree"). Word-anchored to the title start with the marker captured, handed verbatim to
 * [parseEventStatus]; a band whose name contains one of these words is untouched. `SOLD OUT -`
 * leads a title the same way (#1841); it is no status, and [urbanSpreeSoldOut] reads it.
 */
private val STATUS_PREFIX_PATTERN =
    Regex(
        """^\s*(cancelled|canceled|abgesagt|postponed|verschoben|verlegt|relocated|sold\s+out|ausverkauft)\b\s*[-–—:!.]*\s*""",
        RegexOption.IGNORE_CASE
    )

/**
 * A venue/city token appended to titles: the venue's name, the city (optionally as a "Berlin
 * Show" billing), the RAW-Gelände, or a trailing redundant date ("LES SHIRLEY - BERLIN,
 * 15.09.2026").
 */
private const val PLACE_TOKEN = """(?:urban\s*spree|berlin(?:\s+show)?|raw[-\s]?gel(?:ä|ae)nde|\d{1,2}\.\d{1,2}\.\d{2,4})"""

/** One or more [PLACE_TOKEN]s run together by spaces, commas, dashes, pipes or an `@` ("Berlin Show @ Urban Spree"). */
private const val PLACE_RUN = """$PLACE_TOKEN(?:[\s,@|·-]+$PLACE_TOKEN)*"""

/**
 * A trailing venue/city tail in the venue's two spellings: delimiter-introduced (`" - Urban Spree
 * - Berlin"`, `" | Urban Spree Berlin"`, `", BERLIN"`, `" @Urban Spree"`) and the prose `" live
 * at Urban Spree Berlin"`. The delimiter or `live at` is required, so "Isolation Berlin" is never
 * truncated: only "Isolation Berlin - Urban Spree" loses its tail, and the band survives the
 * second pass because a bare space does not open one.
 */
private val VENUE_TAIL_PATTERN =
    Regex(
        """\s*[-–—|,@]+\s*(?:live\s+at\s+)?@?\s*$PLACE_RUN\s*[.,]?\s*$""" +
            """|\s+live\s+at\s+$PLACE_RUN\s*[.,]?\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * A support-billing note appended to a title, usually after a pipe (`"WISBORG Phantomschmerz
 * Tour - BERLIN | Special Guest: The Fright"`). The colon after the marker is required, so
 * `"JUD | Urban Spree Berlin"` is left to [VENUE_TAIL_PATTERN]; the separator is optional.
 */
private val BILLING_NOTE_PATTERN =
    Regex("""\s*[|/–—-]?\s*(?:supports?|openers?|special\s+guests?)\s*:.*$""", RegexOption.IGNORE_CASE)

/**
 * Splits a title into the headline and the support note trailing it, returned verbatim for the
 * subtitle and for [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType],
 * which parses it with [extractSupportFromSubtitle][de.norm.events.scraper.extractSupportFromSubtitle].
 * Unchanged with a `null` note when there is none.
 */
fun splitUrbanSpreeBilling(title: String): Pair<String, String?> {
    val match = BILLING_NOTE_PATTERN.find(title)
    val headline = match?.let { title.substring(0, it.range.first).trim() }
    // A title that is *only* a billing note has no headliner to salvage — keep it whole.
    return if (match == null || headline.isNullOrBlank()) title to null else headline to match.value.trim()
}

/**
 * The [EventStatus] a title's leading marker announces, defaulting to [EventStatus.SCHEDULED].
 */
fun urbanSpreeStatus(title: String): String =
    STATUS_PREFIX_PATTERN
        .find(title)
        ?.let { parseEventStatus(it.groupValues[1]) }
        ?: EventStatus.SCHEDULED.name

/** Whether the title leads with the venue's `SOLD OUT -` marker (`SOLD OUT - Otha`). */
fun urbanSpreeSoldOut(title: String): Boolean =
    STATUS_PREFIX_PATTERN
        .find(title)
        ?.groupValues
        ?.get(1)
        ?.let { SOLD_OUT_MARKER.matches(it) } == true

private val SOLD_OUT_MARKER = Regex("""sold\s+out|ausverkauft""", RegexOption.IGNORE_CASE)

/**
 * Strips the decorations to the billed act(s): the leading status marker ([urbanSpreeStatus])
 * and the trailing venue/city tail, then [cleanEventTitle]. The tail is stripped repeatedly
 * because the venue chains tokens ("Coilguns - Berlin - Urban Spree"), each pass guarded so a
 * title that is only decoration is returned unchanged.
 */
fun cleanUrbanSpreeTitle(title: String): String {
    var current = title.replaceFirst(STATUS_PREFIX_PATTERN, "").trim().ifBlank { title.trim() }
    while (true) {
        val stripped = current.replace(VENUE_TAIL_PATTERN, "").trim()
        if (stripped.isBlank() || stripped == current) break
        current = stripped
    }
    return cleanEventTitle(current).ifBlank { title.trim() }
}

/**
 * The start both `data-dateStart` and the detail hero give a late club night (#1904). It is a
 * placeholder there, not a time, unless the prose says the same ("Doors open at 23:59").
 */
val URBAN_SPREE_LATE_PLACEHOLDER: LocalTime = LocalTime.of(23, 59)

/**
 * A `dd.mm.yy HH:MM` stamp, as a club night's description opens: `25.09.26 21:00 — LATE`.
 */
private val HEADER_DATE_TIME = Regex("""\b(\d{1,2}\.\d{1,2}\.\d{2})\s+(\d{1,2}:\d{2})\b""")

/** The stamp's date half, `25.09.26`. */
private val HEADER_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yy")

/** How far into the description the stamp is looked for: its opening line or two. */
private const val HEADER_SCAN_LENGTH = 120

/**
 * The start a description's opening stamp states, when the stamp names [eventDate]; `null`
 * otherwise. The date must match, because a blurb can quote another show's time.
 */
fun urbanSpreeHeaderStart(
    description: String?,
    eventDate: LocalDate
): LocalTime? {
    val stamp = HEADER_DATE_TIME.find(description?.take(HEADER_SCAN_LENGTH).orEmpty()) ?: return null
    val (date, time) = stamp.destructured
    val stampedDate = runCatching { LocalDate.parse(date, HEADER_DATE_FORMAT) }.getOrNull()
    return if (stampedDate == eventDate) parseTime(time) else null
}

/**
 * One `<name> — DJ set` entry of a club night's running order. The venue's paragraph breaks fall
 * inside entries, so the flat text is read: a name follows a clock time or the previous entry's
 * `DJ set`, which a lookahead leaves for the next match.
 */
private val DJ_SET_ENTRY = Regex("""(?:\d{1,2}:\d{2}|DJ set)\s+([^\s\d—–→][^\d—–→]*?)\s+[—–]\s+(?=DJ set\b)""", RegexOption.IGNORE_CASE)

/** The DJs a description bills as `<name> — DJ set`, in order, each once. */
fun urbanSpreeDjSets(description: String?): List<String> =
    description
        ?.let { text ->
            DJ_SET_ENTRY
                .findAll(text)
                .map { it.groupValues[1].trim() }
                .distinct()
                .toList()
        }.orEmpty()

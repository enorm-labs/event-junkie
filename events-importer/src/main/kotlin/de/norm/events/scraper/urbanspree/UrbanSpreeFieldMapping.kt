package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.parseEventStatus

// Field mapping shared by the Urban Spree overview and detail scrapers: the venue's own
// category labels, and the title cleanup both pages need.
//
// The venue writes its own name and city into almost every event title ("Coilguns -
// Berlin - Urban Spree", "JUD | Urban Spree Berlin", "New Candys (IT Fuzz Club) live at
// Urban Spree Berlin") and prefixes a cancelled show with a status marker ("CANCELLED -
// SOM - Berlin - Urban Spree"). Both scrapers need the same cleanup: the overview so its
// fallback data is usable when a detail page fails, the detail page because it is the
// primary source for the stored title and the title-derived headliner. Every case is asserted
// in UrbanSpreeFieldMappingTest, which is where the examples live.

/**
 * Urban Spree's own category labels, passed to
 * [mapEventType][de.norm.events.scraper.mapEventType] as venue-specific synonyms. The site
 * pluralises every label ("Concerts", "Exhibitions"), which the shared singular table does
 * not cover, and files parts of its programme under labels of its own ("Live Streaming",
 * "Art Fair"). Labels with no musical-event counterpart ("Workshops") are deliberately
 * absent, so they fall through to `null` and the persistence boundary applies the `OTHER`
 * default.
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
 * Percent-encodes the spaces MODX leaves in a media filename, so the stored URL is a valid
 * one. The venue uploads posters under their original names ("FLUXO invites EBONY.jpeg"),
 * and Jsoup's `absUrl` resolves the path verbatim — the raw result is rejected by
 * `URI.create` and by anything else that parses it strictly. Returns `null` for a null or
 * blank input.
 */
fun normalizeAssetUrl(url: String?): String? = url?.takeIf { it.isNotBlank() }?.replace(" ", "%20")

/**
 * A leading status marker on an Urban Spree title — the venue's only cancellation
 * signal, written into the title itself rather than a badge or CSS class ("CANCELLED -
 * SOM - Berlin - Urban Spree"). Word-anchored to the title start with the marker
 * captured, so it is handed verbatim to the shared [parseEventStatus] and a band whose
 * name merely contains one of these words is untouched.
 */
private val STATUS_PREFIX_PATTERN =
    Regex(
        """^\s*(cancelled|canceled|abgesagt|postponed|verschoben|verlegt|relocated)\b\s*[-–—:!.]*\s*""",
        RegexOption.IGNORE_CASE
    )

/**
 * A venue/city token Urban Spree appends to its titles: the venue's own name, the city
 * (optionally as a "Berlin Show" billing), the RAW-Gelände it sits on, or a trailing
 * redundant date ("LES SHIRLEY - BERLIN, 15.09.2026").
 */
private const val PLACE_TOKEN = """(?:urban\s*spree|berlin(?:\s+show)?|raw[-\s]?gel(?:ä|ae)nde|\d{1,2}\.\d{1,2}\.\d{2,4})"""

/** One or more [PLACE_TOKEN]s run together by spaces, commas, dashes, pipes or an `@` ("Berlin Show @ Urban Spree"). */
private const val PLACE_RUN = """$PLACE_TOKEN(?:[\s,@|·-]+$PLACE_TOKEN)*"""

/**
 * A trailing venue/city tail, in the two spellings the venue uses:
 * 1. delimiter-introduced — `" - Urban Spree - Berlin"`, `" | Urban Spree Berlin"`,
 *    `", BERLIN"`, `" @Urban Spree"`; and
 * 2. the prose form `" live at Urban Spree Berlin"`, which carries no delimiter.
 *
 * The delimiter (or the `live at` phrase) is **required**, so a real act whose name ends
 * in a place word — "Isolation Berlin" — is never truncated: only "Isolation Berlin - Urban
 * Spree" loses its tail, and the band survives the second pass because a bare space does not
 * open a tail.
 */
private val VENUE_TAIL_PATTERN =
    Regex(
        """\s*[-–—|,@]+\s*(?:live\s+at\s+)?@?\s*$PLACE_RUN\s*[.,]?\s*$""" +
            """|\s+live\s+at\s+$PLACE_RUN\s*[.,]?\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * A support-billing note the venue appends to a title, usually after a pipe —
 * `"WISBORG Phantomschmerz Tour - BERLIN | Special Guest: The Fright"`. The colon after the
 * marker is required, so a plain pipe-separated venue tail (`"JUD | Urban Spree Berlin"`)
 * is left to [VENUE_TAIL_PATTERN]; the separator itself is optional, so a note written
 * without one is still caught.
 */
private val BILLING_NOTE_PATTERN =
    Regex("""\s*[|/–—-]?\s*(?:supports?|openers?|special\s+guests?)\s*:.*$""", RegexOption.IGNORE_CASE)

/**
 * Splits an Urban Spree title into the headline part and the support-billing note trailing
 * it, so the billed support acts do not end up glued onto the headliner's name.
 *
 * The note is returned verbatim for the caller to store as the subtitle and to hand to
 * [buildArtistsForEventType][de.norm.events.scraper.buildArtistsForEventType], which parses
 * the acts out of it with the shared
 * [extractSupportFromSubtitle][de.norm.events.scraper.extractSupportFromSubtitle]. Returns
 * the title unchanged with a `null` note when there is no such billing.
 */
fun splitUrbanSpreeBilling(title: String): Pair<String, String?> {
    val match = BILLING_NOTE_PATTERN.find(title)
    val headline = match?.let { title.substring(0, it.range.first).trim() }
    // A title that is *only* a billing note has no headliner to salvage — keep it whole.
    return if (match == null || headline.isNullOrBlank()) title to null else headline to match.value.trim()
}

/**
 * Reads the [EventStatus] an Urban Spree title announces via its leading status marker,
 * defaulting to [EventStatus.SCHEDULED] when there is none.
 */
fun urbanSpreeStatus(title: String): String =
    STATUS_PREFIX_PATTERN
        .find(title)
        ?.let { parseEventStatus(it.groupValues[1]) }
        ?: EventStatus.SCHEDULED.name

/**
 * Strips Urban Spree's title decorations to the billed act(s): the leading status marker
 * (captured separately by [urbanSpreeStatus]) and the trailing venue/city tail, then the
 * shared [cleanEventTitle] tidy-up. The tail is stripped repeatedly because the venue
 * chains its tokens across several delimiters ("Coilguns - Berlin - Urban Spree"), and
 * each pass is guarded so a title that is *only* decoration is returned unchanged rather
 * than emptied.
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

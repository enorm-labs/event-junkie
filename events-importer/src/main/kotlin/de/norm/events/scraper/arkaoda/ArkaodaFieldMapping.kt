package de.norm.events.scraper.arkaoda

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix

// Field mapping shared by the two arkaoda scrapers, which render the same event block markup (a
// `<b>` header run, an `<h6>` title, a `<p>` body); keeping the rules here stops the two
// drifting. The header run is parsed in ArkaodaHeader.kt. Every case is asserted in
// ArkaodaFieldMappingTest.

/**
 * Cleans a raw title for storage: [unescapeAddslashes], then [cleanEventTitle].
 */
fun arkaodaTitle(rawTitle: String): String = cleanEventTitle(unescapeAddslashes(rawTitle))

/**
 * Undoes PHP `addslashes` escaping that leaks into the markup: `Post Clients & Friends: 7\"
 * Vinyl Release Party` is echoed escaped. Only `\'`, `\"` and `\\` are undone, so a backslash
 * that is part of a name is left alone.
 */
fun unescapeAddslashes(text: String): String = text.replace(ADDSLASHES_ESCAPE, "$1")

/**
 * Resolves the event type from the venue's [category] label, falling back to the [title].
 * `Konser` (Turkish for concert; arkaoda is the Berlin outpost of the Istanbul venue) is the
 * only label the site emits, so its absence is a signal: an unlabelled night is a DJ night, a
 * vinyl market, a supper club or a release party, never a plain concert. Hence
 * [inferUnmarkedTitleType], a keyword type or `OTHER`, and not
 * [inferConcertVenueType][de.norm.events.scraper.inferConcertVenueType], which would mint the
 * event name as a headliner ([arkaodaArtists]).
 */
fun arkaodaEventType(
    category: String?,
    title: String
): String = mapEventType(category, KONSER_SYNONYM) ?: inferUnmarkedTitleType(title)

/**
 * Extracts the promoter from a `"<promoter> pres./presents[:|-] <event>"` title, the one
 * structured party the venue names (`"pre:sense pres. Volpe (Live)"` to `pre:sense`, `"MILK ME
 * presents: Laura Krieg + Schulverweis"` to `MILK ME`). Empty without such a prefix; applied to
 * every type, since the label books concerts and club nights alike.
 */
fun arkaodaPromoters(title: String): List<String> =
    listOfNotNull(
        PRESENTS_PREFIX
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    )

/**
 * Extracts the ticket-shop link from the description [lines]. The venue pastes the shop URL, in
 * practice a Resident Advisor link, into the prose under a `Tickets:` label:
 * ```
 * Tickets:
 * https://ra.co/events/2448980
 * Door: Limited Tickets at Door
 * ```
 * The first absolute link whose own line or the preceding line mentions "ticket" is taken;
 * requiring the label keeps an artist's Bandcamp, Instagram or press link out, and an unlabelled
 * link is skipped. Prices are not read from the same prose ("€10 Entry on the door", "Door:
 * Limited Tickets at Door"): no reliable delimiter, and a mis-parse shows the wrong price.
 */
fun arkaodaTicketUrl(lines: List<String>): String? =
    lines
        .withIndex()
        .firstNotNullOfOrNull { (index, line) ->
            val url = ABSOLUTE_URL.find(line)?.value ?: return@firstNotNullOfOrNull null
            val labelled =
                TICKET_LABEL.containsMatchIn(line) ||
                    lines.getOrNull(index - 1)?.let { TICKET_LABEL.containsMatchIn(it) } == true
            url.takeIf { labelled }
        }?.trimEnd('.', ',', ')')

/**
 * Derives the billed acts from a `Konser`-labelled event's [title], the only artist source on
 * the site. Narrow, because titles are dominated by series, label and collaboration names
 * (`"Osàre! Editions x arkaoda"`, `"Alonas FFS Fundraiser"`) and a wrongly minted artist is a
 * permanent row:
 * 1. Only `CONCERT` events qualify; an unlabelled night's title is an event name
 * ([arkaodaEventType]).
 * 2. The framing comes off first: a `"<promoter> pres."` prefix ([PRESENTS_PREFIX]), a
 * `"<series>: "` prefix ([SERIES_PREFIX]), a trailing `" at Arkaoda"`.
 * 3. A title that still reads as a compound event label yields nothing
 * ([isCompoundEventLabel]): a spaced dash, an ` x ` collaboration marker, or a format word
 * (`release`, `takeover`, `fundraiser`). This drops the real acts out of `"Grumpy Pieces
 * release; Harmonious Thelonious (Live)"` rather than risk minting `"Remise Takeover"`.
 * 4. The rest splits on commas and space-padded `+` / `/` ([ACT_SEPARATOR], the padding keeping
 * `"(PL/USA)"` intact), then per conjunction boundary via [splitSegmentOnConjunctions].
 *
 * Each act loses a trailing country tag ([COUNTRY_TAG_SUFFIX]) and the shared tails
 * ([stripArtistSuffix]), and is dropped if [isNonArtistName]. All survivors are `HEADLINER` in
 * title order: arkaoda publishes no billing hierarchy.
 */
@Suppress("ReturnCount") // Guard clauses for the non-concert and compound-label cases are clearer than nesting
fun arkaodaArtists(
    title: String,
    eventType: String
): List<ScrapedArtist> {
    if (eventType != EventType.CONCERT.name) return emptyList()
    val billing = stripBillingFraming(title)
    if (isCompoundEventLabel(billing)) return emptyList()
    return billing
        .split(ACT_SEPARATOR)
        .flatMap { splitSegmentOnConjunctions(it) }
        .map { stripArtistSuffix(it.trim().replace(COUNTRY_TAG_SUFFIX, "")) }
        .filter { it.isNotBlank() && !isNonArtistName(it) }
        .distinct()
        .map { ScrapedArtist(name = it) }
}

/** Strips the promoter / series / venue framing around the billing in a title (see [arkaodaArtists] step 2). */
private fun stripBillingFraming(title: String): String =
    title
        .replaceFirst(PRESENTS_PREFIX, "")
        .replaceFirst(SERIES_PREFIX, "")
        .replace(AT_VENUE_SUFFIX, "")
        .trim()

/**
 * True when a title still reads as a compound event label ([arkaodaArtists] step 3).
 */
private fun isCompoundEventLabel(billing: String): Boolean =
    RESIDUAL_DASH.containsMatchIn(billing) ||
        COLLAB_MARKER.containsMatchIn(billing) ||
        EVENT_FORMAT_WORD.containsMatchIn(billing)

/** The venue's sole category label — Turkish for "concert"; see [arkaodaEventType]. */
private val KONSER_SYNONYM: Map<String, String> = mapOf("konser" to EventType.CONCERT.name)

/** The `\'`, `\"` and `\\` sequences PHP `addslashes` leaves in the rendered markup. */
private val ADDSLASHES_ESCAPE = Regex("""\\([\\'"])""")

/**
 * A leading `"<promoter> pres./presents/präsentiert[:|-] "` prefix, capturing the promoter. The
 * trailing `\s+` is the guard: "… Presley Tribute" has no whitespace after the marker, and the
 * `\s+` before it makes the marker its own word.
 */
private val PRESENTS_PREFIX =
    Regex("""^(\S.*?)\s+pr[eä]s(?:ent(?:s|ed|iert|ieren)?)?\.?\s*[-–—:]?\s+(?=\S)""", RegexOption.IGNORE_CASE)

/**
 * A leading `"<series>: "` prefix whose remainder is the billing (`"Signal To Noise: Vicente
 * Yáñez, …"`, `"Miaan Nights: 10or Møsaic"`). The required whitespace after the colon protects a
 * colon inside a name: `"pre:sense"` is never cut to `"sense"`.
 */
private val SERIES_PREFIX = Regex("""^[^:]*\S:\s+""")

/** An absolute `http(s)` link pasted into the description prose. */
private val ABSOLUTE_URL = Regex("""https?://\S+""")

/** The `Tickets:` label the venue writes above (or beside) a ticket-shop link. */
private val TICKET_LABEL = Regex("""\btickets?\b""", RegexOption.IGNORE_CASE)

/** A trailing `" at Arkaoda"` venue tail the venue appends to some billings. */
private val AT_VENUE_SUFFIX = Regex("""\s+at\s+arkaoda\s*$""", RegexOption.IGNORE_CASE)

/** A residual spaced dash — the signature of a compound event label, not an act (as on Gretchen). */
private val RESIDUAL_DASH = Regex("""\s[-–—]\s""")

/** An ` x ` collaboration marker, which on arkaoda joins two *labels* or series, not two acts. */
private val COLLAB_MARKER = Regex("""\sx\s""", RegexOption.IGNORE_CASE)

/** Event-format words that mark a title as naming the occasion rather than the act. */
private val EVENT_FORMAT_WORD =
    Regex("""\b(?:release|takeover|fundraiser|market)\b""", RegexOption.IGNORE_CASE)

/**
 * Act separators: an unpadded comma and a space-padded `+` or `/`; the padding keeps the `/`
 * in `"Marta Warelis (PL/USA)"` from splitting.
 */
private val ACT_SEPARATOR = Regex("""\s*,\s*|\s+[+/]\s+""")

/**
 * A trailing origin tag of uppercase codes (`"(PL/USA)"`, `"(PL/DE)"`), anchored to two- and
 * three-letter all-caps, so `"Sickboyrari (Black Kray)"` and `"(Thailand- Live)"` are untouched.
 */
private val COUNTRY_TAG_SUFFIX = Regex("""\s*\([A-Z]{2,3}(?:\s*[/,]\s*[A-Z]{2,3})*\)\s*$""")

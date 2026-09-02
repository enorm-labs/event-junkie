package de.norm.events.scraper.arkaoda

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix

// Field mapping shared by the two arkaoda scrapers. The listing and the detail page
// render the *same* event block markup (a `<b>` header run, an `<h6>` title and a
// `<p>` body), so every rule below is used by both — keeping them here is what stops
// ArkaodaOverviewPageScraper and ArkaodaDetailPageScraper from drifting apart. The
// header run itself is parsed in ArkaodaHeader.kt. Every case is asserted in
// ArkaodaFieldMappingTest, which is where the examples live.

/**
 * Cleans a raw arkaoda title for storage: undoes the site's leaked PHP escapes
 * ([unescapeAddslashes]) and strips the shared trailing title noise
 * ([cleanEventTitle]).
 */
fun arkaodaTitle(rawTitle: String): String = cleanEventTitle(unescapeAddslashes(rawTitle))

/**
 * Undoes PHP `addslashes` escaping that leaks into arkaoda's rendered markup —
 * `Post Clients & Friends: 7\" Vinyl Release Party` is stored escaped and echoed
 * into the page verbatim, so the backslashes reach the scraper and would otherwise
 * be persisted as part of the title.
 *
 * Only the three sequences `addslashes` produces are undone (`\'`, `\"`, `\\`), so a
 * backslash that is genuinely part of a name is left alone.
 */
fun unescapeAddslashes(text: String): String = text.replace(ADDSLASHES_ESCAPE, "$1")

/**
 * Resolves the event type from the venue's own [category] label, falling back to the
 * [title] when there is none.
 *
 * `Konser` (Turkish for concert — arkaoda is the Berlin outpost of the Istanbul
 * venue) is the **only** label the site ever emits, so its absence is itself a
 * signal: an unlabelled night is a DJ/club night, a vinyl market, a supper club or a
 * release party, never a plain concert. The fallback is therefore
 * [inferUnmarkedTitleType] — a keyword type or `OTHER` — and deliberately *not*
 * [inferConcertVenueType][de.norm.events.scraper.inferConcertVenueType]: defaulting
 * an unlabelled night to `CONCERT` would both mislabel it and mint its event name as
 * a headliner (see [arkaodaArtists]).
 */
fun arkaodaEventType(
    category: String?,
    title: String
): String = mapEventType(category, KONSER_SYNONYM) ?: inferUnmarkedTitleType(title)

/**
 * Extracts the promoter from a `"<promoter> pres./presents[:|-] <event>"` title —
 * the one structured party the venue names anywhere in its markup (`"pre:sense pres.
 * Volpe (Live)"` → `pre:sense`, `"MILK ME presents: Laura Krieg + Schulverweis"` →
 * `MILK ME`).
 *
 * Returns an empty list when the title carries no such prefix. Applied to every
 * event regardless of type: the label books both concerts and club nights.
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
 * Extracts the ticket-shop link from an event's description [lines].
 *
 * The venue runs no ticket integration: when a show sells advance tickets it pastes
 * the shop URL — in practice a Resident Advisor event link — into the prose under a
 * `Tickets:` label:
 * ```
 * Tickets:
 * https://ra.co/events/2448980
 * Door: Limited Tickets at Door
 * ```
 * A URL is unambiguous once labelled, so the first absolute link whose own line or
 * immediately preceding line mentions "ticket" is taken. Requiring the label is what
 * keeps an artist's Bandcamp, Instagram or press link — which descriptions also carry —
 * out of the ticket field; a link the venue pasted without one is skipped rather than
 * guessed at.
 *
 * Prices are deliberately *not* read from the same prose ("€10 Entry on the door",
 * "Door: Limited Tickets at Door"): unlike a URL they have no reliable delimiter and a
 * mis-parse would show users the wrong price, so those fields stay null.
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
 * Derives the billed acts from a `Konser`-labelled event's [title], the only artist source on the
 * site — there is no lineup markup, no JSON-LD and no `og:` act field anywhere.
 *
 * The rule is deliberately narrow, because arkaoda titles are dominated by series, label and
 * collaboration names rather than billings (`"Osàre! Editions x arkaoda"`, `"Alonas FFS Fundraiser"`),
 * and a wrongly-minted artist becomes a permanent row in the artist table:
 * 1. **Only `CONCERT` events qualify.** The venue's own `Konser` label is what confirms the title
 *    bills live acts; an unlabelled night's title is an event name ([arkaodaEventType]).
 * 2. **The framing is removed first** — a `"<promoter> pres."` prefix ([PRESENTS_PREFIX]; the
 *    promoter is captured separately by [arkaodaPromoters]), a `"<series>: "` prefix
 *    ([SERIES_PREFIX]), and a trailing `" at Arkaoda"` venue tail.
 * 3. **A title that still reads as a compound event label yields nothing**
 *    ([isCompoundEventLabel]) — a spaced dash, an ` x ` collaboration marker, or a format word
 *    (`release`, `takeover`, `fundraiser`). This is the conservative half of the rule: it drops the
 *    real acts out of `"Grumpy Pieces release; Harmonious Thelonious (Live)"` rather than risk
 *    minting `"Remise Takeover"` as a band.
 * 4. **What remains is split into acts** on commas and space-padded `+` / `/` ([ACT_SEPARATOR] — the
 *    padding keeps a `"(PL/USA)"` country tag intact), then per conjunction boundary via the shared
 *    [splitSegmentOnConjunctions], so a backing-band tail stays attached to its act.
 *
 * Each act is then stripped of a trailing country tag ([COUNTRY_TAG_SUFFIX]) and the shared
 * tour/live/format tails ([stripArtistSuffix]), and dropped if it is not a performer
 * ([isNonArtistName]). All survivors are billed `HEADLINER` in title order: arkaoda publishes no
 * billing hierarchy, so promoting the first would invent one.
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
 * True when a title still reads as a compound *event* label rather than a clean act
 * billing — see [arkaodaArtists] step 3 for why this rejects rather than salvages.
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
 * A leading `"<promoter> pres./presents/präsentiert[:|-] "` prefix, capturing the
 * promoter. The marker accepts the abbreviated `pres.` spelling the venue favours and
 * the German `präsentiert`, and an optional `:` / dash before the event name.
 *
 * The trailing `\s+` is what keeps it safe: a name merely *starting* with those
 * letters ("… Presley Tribute") has no whitespace after the marker and cannot match,
 * and the `\s+` before it means the marker must be its own word.
 */
private val PRESENTS_PREFIX =
    Regex("""^(\S.*?)\s+pr[eä]s(?:ent(?:s|ed|iert|ieren)?)?\.?\s*[-–—:]?\s+(?=\S)""", RegexOption.IGNORE_CASE)

/**
 * A leading `"<series>: "` prefix, whose remainder is the billing
 * (`"Signal To Noise: Vicente Yáñez, …"`, `"Miaan Nights: 10or Møsaic"`).
 *
 * The required whitespace after the colon is the guard that protects a colon *inside*
 * a name — `"pre:sense"` has none, so it is never cut down to `"sense"`.
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
 * Act separators in a billing: a comma (which the venue writes unpadded) and a
 * **space-padded** `+` or `/`. The padding requirement is what keeps a country tag
 * intact — the `/` in `"Marta Warelis (PL/USA)"` is not a separator.
 */
private val ACT_SEPARATOR = Regex("""\s*,\s*|\s+[+/]\s+""")

/**
 * A trailing origin tag of uppercase country/region codes — `"(PL/USA)"`,
 * `"(PL/DE)"`. Anchored to two- and three-letter all-caps codes, so a parenthesized
 * alias (`"Sickboyrari (Black Kray)"`) or a format note (`"(Thailand- Live)"`) is
 * left untouched.
 */
private val COUNTRY_TAG_SUFFIX = Regex("""\s*\([A-Z]{2,3}(?:\s*[/,]\s*[A-Z]{2,3})*\)\s*$""")

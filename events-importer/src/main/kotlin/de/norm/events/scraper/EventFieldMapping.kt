package de.norm.events.scraper

import de.norm.events.event.EventStatus
import java.math.BigDecimal
import java.time.LocalTime

// Field-level mapping for scraped events: status badges, doors/start ordering,
// title cleanup, and free-entry detection. Event-type and artist-name mapping live
// in EventTypeMapping.kt and ArtistNameMapping.kt.

/**
 * Maps a venue status-badge text (German or English) to an [EventStatus] name.
 *
 * Matching is case-insensitive. "sold out" / "ausverkauft" is intentionally
 * **not** a status — venues capture it separately as the `soldOut` flag, leaving
 * the status [SCHEDULED][EventStatus.SCHEDULED]. Shared across Kulturhäuser-platform
 * scrapers (Astra, Lido) whose badges use these conventional labels.
 */
fun parseEventStatus(statusText: String): String {
    val text = statusText.lowercase()
    return when {
        text.contains("abgesagt") || text.contains("cancel") -> EventStatus.CANCELLED.name
        text.contains("verschoben") || text.contains("postpon") -> EventStatus.POSTPONED.name
        text.contains("verlegt") || text.contains("reloc") -> EventStatus.RELOCATED.name
        else -> EventStatus.SCHEDULED.name
    }
}

/**
 * Maps a schema.org `eventStatus` URL onto an [EventStatus] name.
 *
 * The vocabulary is an explicit machine-readable contract — `EventScheduled`, `EventCancelled`,
 * `EventPostponed`, `EventRescheduled`, `EventMovedOnline` — so a venue that publishes it is
 * matched on the term rather than on the German prose [parseEventStatus] has to read. Both the
 * `http://` and `https://` spellings occur in the wild, and some sites emit the bare term, so only
 * the trailing term is compared. Anything unrecognized (or absent) is
 * [SCHEDULED][EventStatus.SCHEDULED].
 *
 * `EventMovedOnline` maps to [RELOCATED][EventStatus.RELOCATED]: the show still happens, just not
 * where it was billed — the closest the model has to "moved".
 */
fun parseSchemaEventStatus(status: String?): String {
    val term = status?.substringAfterLast('/').orEmpty()
    return when (term) {
        "EventCancelled" -> EventStatus.CANCELLED.name
        "EventPostponed", "EventRescheduled" -> EventStatus.POSTPONED.name
        "EventMovedOnline" -> EventStatus.RELOCATED.name
        else -> EventStatus.SCHEDULED.name
    }
}

/**
 * Returns the (doors, start) pair with doors never later than start.
 *
 * Doors open no later than the show begins, so when a source lists the two in the
 * wrong order — e.g. SO36's `"Einlass: 19:30, Beginn: 19:00"` — the labels were
 * transposed at the source; swapping them recovers the intended times. Only
 * reorders when **both** times are present and doors is strictly after start; a
 * single time, equal times, or an already-valid pair is returned unchanged. Applied
 * once at the [ScrapedEvent.toEventEntity] persistence boundary, so every venue is
 * covered without each scraper repeating the check.
 */
fun orderDoorsBeforeStart(
    doors: LocalTime?,
    start: LocalTime?
): Pair<LocalTime?, LocalTime?> = if (doors != null && start != null && doors > start) start to doors else doors to start

/**
 * A leading "verlegt in den <venue> –" relocation note a venue prepends to a moved show's
 * title (Mikropol's `"-verlegt in den Frannz Club – CULTURE WARS"`, Metropol's
 * `"Verlegt ins Bi Nuu – BRKN"`). Such venues encode the relocation in the title prose rather
 * than a status class, so the note is stripped to recover the real act name for both the stored
 * title and the derived headliner; the `RELOCATED` status is set separately from the same
 * "verlegt" keyword via [parseEventStatus]. An optional leading dash and the trailing dash
 * separator (`-`/`–`/`—`) are consumed. Both German contractions of the preposition are
 * accepted — Mikropol writes "verlegt **in** den Frannz Club", Metropol "Verlegt **ins** Bi Nuu".
 */
private val RELOCATION_PREFIX_PATTERN =
    Regex("""^\s*[-–—]?\s*verlegt\s+ins?\s+.+?\s*[-–—]\s*""", RegexOption.IGNORE_CASE)

/**
 * Strips a leading [RELOCATION_PREFIX_PATTERN] from a title, keeping the input unchanged
 * when there is no such prefix or when stripping would leave nothing.
 */
fun stripRelocationPrefix(title: String): String {
    val stripped = title.replaceFirst(RELOCATION_PREFIX_PATTERN, "").trim()
    return stripped.ifBlank { title.trim() }
}

/**
 * Trailing noise venues append to an *event title* that must not become part of the stored
 * title (nor of a title-derived headliner artist):
 * - a "Nachholtermin vom <date>" / "(verschoben aus <year>)" reschedule note or a
 *   "Hochverlegung" relocation note — the note itself is still read as the event's `POSTPONED`
 *   status, from the *raw* title, before the title is cleaned,
 * - a "-verlegt ins <venue>-" moved-house note (Frannz spells the relocation as a *suffix* where
 *   Metropol uses the "Verlegt ins <venue> –" prefix [stripRelocationPrefix] handles) — likewise
 *   read as the event's `RELOCATED` status from the raw title first. Anchored on the following
 *   "ins"/"nach" so a title merely containing the word is never truncated,
 * - a "(ausverkauft)" / "ausverkauft" sold-out annotation — a status, not a name; Frannz in
 *   particular never derives sold-out from prose, so it is pure noise here, and stripping it
 *   keeps "… (ausverkauft)" and its non-sold-out twin from splitting into two artists,
 * - any stray trailing dash.
 *
 * Each alternative is word-/end-anchored, so mid-title text (e.g. an "ausverkauften" mention
 * that only ever reaches descriptions) is never touched. This is the title-level counterpart
 * of the tail [ARTIST_SUFFIX_PATTERN] strips off an artist name.
 */
private val TITLE_NOISE_PATTERN =
    Regex(
        """\s+[-–—(]*\s*(?:nachholtermin|hochverlegung|verschoben|verlegt\s+(?:ins|nach))\b.*$""" +
            """|\s+[-–—(]*\s*ausverkauft!?\s*\)?\s*$""" +
            """|\s+[-–—]\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a trailing rescheduled-show note and stray trailing dash from an event title
 * so the stored, user-visible title stays clean — "Iggi Kelly Nachholtermin vom
 * 28.04.26-" → "Iggi Kelly". Returns the input unchanged when there is no such tail, or
 * when stripping would leave nothing.
 *
 * Zero-width characters are removed and runs of whitespace collapsed to a single space first.
 * A venue's own markup decides how much space lands between two words — a line break inside the
 * heading, a stray double space in the CMS — and that is presentation, not part of the name
 * ("Adventurous Juan (DJ-Set)", "Lucas Lauriente – Stand Up 2026"). Collapsing before the tail
 * patterns run also keeps those patterns keyed on a single space, and normalizes the title a
 * headliner is derived from. A [ZERO_WIDTH] character is invisible by definition, so it is never
 * part of a name either — it reaches a title when an editor pastes one in (MAAYA's "HOMECOMING DJ
 * WORKSHOP") — and it is dropped rather than collapsed, since `\s` does not match it.
 */
fun cleanEventTitle(title: String): String {
    val collapsed = title.replace(ZERO_WIDTH, "").trim().replace(WHITESPACE_RUN, " ")
    val stripped = collapsed.replace(TITLE_NOISE_PATTERN, "").trim()
    return stripped.ifBlank { collapsed }
}

/**
 * A run of whitespace (including a line break) inside a title, collapsed to one space.
 *
 * The two non-breaking spaces are listed explicitly because Java's `\s` matches ASCII whitespace
 * only, while a CMS editor produces them without meaning to — Colosseum's "JOSH. Solo - Wer\u00A0singt
 * dann Lieder für dich?" is one such pasted title. They render as an ordinary space, so a title
 * that keeps them looks right while no longer matching a search for the words around them.
 */
private val WHITESPACE_RUN = Regex("""[\s\u00A0\u202F]+""")

/**
 * Invisible formatting characters that carry no meaning in an event title: the zero-width
 * space, non-joiner, joiner and the byte-order mark. None is whitespace to `\s`, so each would
 * otherwise survive both the trim and the collapse and end up in the stored title.
 */
private val ZERO_WIDTH = Regex("""[\u200B-\u200D\uFEFF]""")

/**
 * Free-entry phrases unambiguous enough to detect from any text field (title or
 * price note). Multi-word, so they won't collide with band or festival names.
 */
private val FREE_PHRASES =
    listOf(
        "eintritt frei",
        "freier eintritt",
        "kostenloser eintritt",
        "free entry",
        "free admission"
    )

/**
 * Single-word free markers, only scanned within the pricing-scoped [ScrapedEvent.priceNote]
 * (never the title/subtitle) to avoid false positives from names like "Freedom Festival"
 * or "Freikörperkultur". Word-boundary matched, so "free" won't match "freestyle".
 */
private val FREE_TOKENS = listOf("free", "frei", "gratis", "kostenlos", "umsonst")

private val FREE_PHRASE_PATTERN =
    Regex(FREE_PHRASES.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

private val FREE_TOKEN_PATTERN =
    Regex("""\b(${FREE_TOKENS.joinToString("|") { Regex.escape(it) }})\b""", RegexOption.IGNORE_CASE)

/**
 * Detects whether an event is free to attend, from its prices and text.
 *
 * A *positive* signal is required — an absent price means the price is **unknown**,
 * not free — so this returns `true` only for:
 * - an explicit €0 presale or box-office price, or
 * - an unambiguous free-entry phrase ([FREE_PHRASES]) in the title or price note, or
 * - a single-word free marker ([FREE_TOKENS]) in the price note (pricing-scoped, so
 *   an artist name in the title can't trigger it).
 */
fun detectFree(
    pricePresale: BigDecimal? = null,
    priceBoxOffice: BigDecimal? = null,
    priceNote: String? = null,
    title: String? = null
): Boolean {
    val hasZeroPrice = pricePresale?.signum() == 0 || priceBoxOffice?.signum() == 0
    val phraseInTitle = title?.let { FREE_PHRASE_PATTERN.containsMatchIn(it) } ?: false
    val markerInNote =
        priceNote?.let { FREE_PHRASE_PATTERN.containsMatchIn(it) || FREE_TOKEN_PATTERN.containsMatchIn(it) } ?: false
    return hasZeroPrice || phraseInTitle || markerInNote
}

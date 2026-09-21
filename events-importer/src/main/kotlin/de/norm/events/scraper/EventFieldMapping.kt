package de.norm.events.scraper

import de.norm.events.event.EventStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

// Field-level mapping for scraped events: status badges, doors/start ordering, title cleanup,
// free-entry detection.

/**
 * Maps a venue status-badge text, German or English, to an [EventStatus] name, case-insensitive.
 * "sold out" / "ausverkauft" is not a status: venues capture it as `soldOut`. A move is also "new
 * venue" / "neuer Ort", the badge Lido and Gretchen print beside the note.
 */
fun parseEventStatus(statusText: String): String {
    val text = statusText.lowercase()
    return when {
        CANCELLED_TEXT.containsMatchIn(text) -> EventStatus.CANCELLED.name
        text.contains("verschoben") || text.contains("postpon") || VERLEGT_AUF_DATUM.containsMatchIn(text) -> EventStatus.POSTPONED.name
        text.contains("verlegt") || text.contains("reloc") || text.contains("new venue") || text.contains("neuer ort") -> EventStatus.RELOCATED.name
        else -> EventStatus.SCHEDULED.name
    }
}

/**
 * "auf den 30.05.2027 verlegt" moves the date, not the house: a postponement written with the
 * relocation verb (Hole 44). Only a date after "auf" counts; "verlegt ins" stays a move.
 */
private val VERLEGT_AUF_DATUM = Regex("""verlegt\s+auf\s+(?:den\s+)?\d|auf\s+(?:den\s+)?\d[\d.]*\s+verlegt""")

/**
 * A cancellation in a badge: `abgesagt`, `Absage` (#1560), `cancel…`, "fällt aus" / "fällt
 * leider aus" / "entfällt" with or without the umlaut (Wild at Heart types `faellt`).
 */
private val CANCELLED_TEXT = Regex("""abgesagt|absage|cancel|\b(?:f(?:ä|ae)llt\s+(?:\w+\s+)?aus|entf(?:ä|ae)llt)\b""")

/**
 * A status word a venue writes into the title itself: Kantine am Berghain's `Olga Myko -
 * Abgesagt`, Wild at Heart's `Da Konzert von Scarfold und Los Mierda faellt leider aus!`, Uber
 * Eats Music Hall's `ABSAGE: …` (#1560). Word-anchored, and the bare "cancel" of
 * [parseEventStatus] is not accepted: "Cancel Culture" is a film.
 */
private val TITLE_STATUS_PATTERN =
    Regex(
        """\b(?:abgesagt|absage|cancell?ed|f(?:ä|ae)llt\s+(?:\w+\s+)?aus|entf(?:ä|ae)llt|verschoben|verlegt)\b""",
        RegexOption.IGNORE_CASE
    )

/**
 * The status a [title] carries in its own words ([TITLE_STATUS_PATTERN]), or `null`. Applied at
 * [ScrapedEvent.toEventEntity] to a row whose scraper found no badge (#1493).
 */
fun parseTitleStatus(title: String): String? = TITLE_STATUS_PATTERN.find(title)?.let { parseEventStatus(it.value) }

/**
 * A cancellation marker glued to the front or end of a title (`Olga Myko - Abgesagt`,
 * `(cancelled) The Act`, `The Act [ABGESAGT!]`) with its separator and brackets. A
 * "verschoben"/"verlegt" tail is [cleanEventTitle]'s, and a sentence that is the notice (Wild
 * at Heart) stays the title.
 */
private val TITLE_STATUS_MARKER =
    Regex(
        """^\s*[(\[]?\s*(?:abgesagt|absage|cancell?ed)!?\s*[)\]]?\s*[-–—:|]*\s*""" +
            """|\s*[-–—:|]*\s*[(\[]?\s*(?:abgesagt|absage|cancell?ed)!?\s*[)\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a leading or trailing [TITLE_STATUS_MARKER]; unchanged when none or when stripping
 * would leave nothing.
 */
fun stripTitleStatusMarker(title: String): String {
    val stripped = title.replace(TITLE_STATUS_MARKER, "").trim()
    return stripped.ifBlank { title.trim() }
}

/**
 * Maps a schema.org `eventStatus` URL onto an [EventStatus] name, on the trailing term, since
 * `http://`, `https://` and the bare term all occur. Unrecognized or absent is
 * [SCHEDULED][EventStatus.SCHEDULED]. `EventMovedOnline` maps to
 * [RELOCATED][EventStatus.RELOCATED], the closest the model has to "moved".
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
 * Returns (doors, start) with doors never later than start: SO36's `"Einlass: 19:30, Beginn:
 * 19:00"` transposed the labels. Reorders only when both are present and doors is strictly
 * after start. Applied once at [ScrapedEvent.toEventEntity].
 */
fun orderDoorsBeforeStart(
    doors: LocalTime?,
    start: LocalTime?
): Pair<LocalTime?, LocalTime?> = if (doors != null && start != null && doors > start) start to doors else doors to start

/**
 * Whether [presale] is dearer than [boxOffice]. Advance sale is never priced above the door, so
 * this is a scraper reading the wrong number: a shop's fee-inclusive figure (Soda, #1583) or a
 * zero meant as "no door sale". Reported at [ScrapedEvent.toEventEntity] and stored as scraped;
 * the correction belongs in that source's parser.
 */
fun presaleAboveDoor(
    presale: BigDecimal?,
    boxOffice: BigDecimal?
): Boolean = presale != null && boxOffice != null && presale > boxOffice

/**
 * The day an event starting at [start] on [eventDate] ends, given only the end [time] the venue
 * printed (ADR-029): `23:00 – 06:00` rolls to the next morning, `22:00 – 23:30` stays. A venue
 * that prints the end's date sets `endDate` directly.
 */
fun endOn(
    eventDate: LocalDate,
    start: LocalTime?,
    time: LocalTime
): LocalDate = if (start != null && time <= start) eventDate.plusDays(1) else eventDate

/**
 * A leading "verlegt in den <venue> –" relocation note (Mikropol's `"-verlegt in den Frannz Club
 * – CULTURE WARS"`, Metropol's `"Verlegt ins Bi Nuu – BRKN"`), stripped to recover the act name
 * for the title and the headliner; the `RELOCATED` status comes from [parseEventStatus]. An
 * optional leading dash and the trailing `-`/`–`/`—` are consumed; both "in den" and "ins" are
 * accepted.
 */
private val RELOCATION_PREFIX_PATTERN =
    Regex("""^\s*[-–—]?\s*verlegt\s+ins?\s+.+?\s*[-–—]\s*""", RegexOption.IGNORE_CASE)

/**
 * Strips a leading [RELOCATION_PREFIX_PATTERN]; unchanged when none or when stripping would
 * leave nothing.
 */
fun stripRelocationPrefix(title: String): String {
    val stripped = title.replaceFirst(RELOCATION_PREFIX_PATTERN, "").trim()
    return stripped.ifBlank { title.trim() }
}

/**
 * Trailing noise venues append to a title that must not reach the stored title or a
 * title-derived headliner: a "Nachholtermin vom <date>" / "(verschoben aus <year>)" /
 * "Hochverlegung" note (read as `POSTPONED` from the raw title first); a "-verlegt ins <venue>-"
 * suffix (Frannz's spelling of Metropol's prefix; read as `RELOCATED` first), anchored on the
 * following "ins"/"nach"; a "(ausverkauft)" annotation, which Frannz never derives sold-out from
 * and which would split "… (ausverkauft)" and its twin into two artists; any stray trailing
 * dash. Each alternative is word- or end-anchored, so "ausverkauften" mid-title is never
 * touched. The title-level counterpart of [ARTIST_SUFFIX_PATTERN].
 */
private val TITLE_NOISE_PATTERN =
    Regex(
        """\s+[-–—(]*\s*(?:nachholtermin|hochverlegung|verschoben|verlegt\s+(?:ins|nach))\b.*$""" +
            """|\s+[-–—(]*\s*ausverkauft!?\s*\)?\s*$""" +
            """|\s+[-–—]\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a trailing rescheduled-show note and stray dash: "Iggi Kelly Nachholtermin vom
 * 28.04.26-" to "Iggi Kelly". Unchanged when there is no tail or stripping would leave nothing.
 * Zero-width characters are removed and whitespace runs collapsed first: a line break inside
 * the heading or a double space in the CMS is presentation, not the name ("Adventurous Juan
 * (DJ-Set)", "Lucas Lauriente – Stand Up 2026"), and the tail patterns key on a single space. A
 * [ZERO_WIDTH] character reaches a title when an editor pastes one (MAAYA's "HOMECOMING DJ
 * WORKSHOP"); `\s` does not match it, so it is dropped.
 */
fun cleanEventTitle(title: String): String {
    val collapsed = title.replace(ZERO_WIDTH, "").trim().replace(WHITESPACE_RUN, " ")
    val stripped = collapsed.replace(TITLE_NOISE_PATTERN, "").trim()
    return stripped.ifBlank { collapsed }
}

/**
 * A run of whitespace inside a title, collapsed to one space. The two non-breaking spaces are
 * listed because Java's `\s` matches ASCII whitespace only, and a CMS editor produces them
 * (Colosseum's "JOSH. Solo - Wer singt dann Lieder für dich?"); a title that keeps them
 * looks right and no longer matches a search.
 */
private val WHITESPACE_RUN = Regex("""[\s\u00A0\u202F]+""")

/**
 * Invisible formatting characters: zero-width space, non-joiner, joiner, byte-order mark. None
 * is whitespace to `\s`, so each would survive the trim and the collapse.
 */
private val ZERO_WIDTH = Regex("""[\u200B-\u200D\uFEFF]""")

/**
 * Free-entry phrases unambiguous enough for any text field; multi-word, so they cannot collide
 * with a band or festival name.
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
 * Single-word free markers, scanned only within [ScrapedEvent.priceNote], never the title, to
 * avoid "Freedom Festival" or "Freikörperkultur". Word-boundary matched, so "free" is not
 * "freestyle".
 */
private val FREE_TOKENS = listOf("free", "frei", "gratis", "kostenlos", "umsonst")

private val FREE_PHRASE_PATTERN =
    Regex(FREE_PHRASES.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

private val FREE_TOKEN_PATTERN =
    Regex("""\b(${FREE_TOKENS.joinToString("|") { Regex.escape(it) }})\b""", RegexOption.IGNORE_CASE)

/**
 * Whether an event is free. A positive signal is required, since an absent price is unknown,
 * not free: an explicit €0 price, a [FREE_PHRASES] match in the title or price note, or a
 * [FREE_TOKENS] match in the price note.
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

package de.norm.events.scraper

import de.norm.events.common.foldTypedApostrophes
import de.norm.events.event.EventStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

// Field-level mapping for scraped events: status badges, doors/start ordering, title cleanup,
// free-entry detection.

/**
 * Maps a venue status-badge text, German or English, to an [EventStatus] name, case-insensitive.
 * "sold out" / "ausverkauft" is not a status: venues capture it as `soldOut`. A move is also "new
 * venue" / "neuer Ort", the badge Lido and Gretchen print beside the note, and the AEG houses'
 * `VENUE ÄNDERUNG` title prefix ([MOVED_TEXT], #1771).
 */
fun parseEventStatus(statusText: String): String {
    val text = statusText.lowercase()
    return when {
        CANCELLED_TEXT.containsMatchIn(text) -> EventStatus.CANCELLED.name
        POSTPONED_TEXT.containsMatchIn(text) || VERLEGT_AUF_DATUM.containsMatchIn(text) -> EventStatus.POSTPONED.name
        MOVED_TEXT.containsMatchIn(text) -> EventStatus.RELOCATED.name
        else -> EventStatus.SCHEDULED.name
    }
}

/**
 * A move, in any of the words a venue writes it: the verb, the badge Lido and Gretchen print, and
 * the AEG houses' `VENUE ÄNDERUNG` — which names the change rather than the verb, so none of the
 * others reached it and the show published as if it still happened there (#1771). Both spellings
 * of the umlaut, and the compound with or without its space.
 */
private val MOVED_TEXT =
    Regex("""verlegt|reloc|new venue|neuer ort|venue\s?(?:ä|ae)nderung""", RegexOption.IGNORE_CASE)

/** A new date, in the words a badge writes it: "verschoben", "postponed", "rescheduled" (Privatclub). */
private val POSTPONED_TEXT = Regex("""verschoben|postpon|reschedul""")

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
 * Eats Music Hall's `ABSAGE: …` (#1560), Uber Arena's `VENUE ÄNDERUNG: …` (#1771).
 * Word-anchored, and the bare "cancel" of [parseEventStatus] is not accepted: "Cancel Culture"
 * is a film.
 */
private val TITLE_STATUS_PATTERN =
    Regex(
        """\b(?:abgesagt|absage|cancell?ed|f(?:ä|ae)llt\s+(?:\w+\s+)?aus|entf(?:ä|ae)llt|verschoben|verlegt""" +
            """|venue\s?(?:ä|ae)nderung)\b""",
        RegexOption.IGNORE_CASE
    )

/**
 * The status a [title] carries in its own words ([TITLE_STATUS_PATTERN]), or `null`. Applied at
 * [ScrapedEvent.toEventEntity] to a row whose scraper found no badge (#1493).
 */
fun parseTitleStatus(title: String): String? = TITLE_STATUS_PATTERN.find(title)?.let { parseEventStatus(it.value) }

/**
 * A status marker glued to the front or end of a title (`Olga Myko - Abgesagt`, `(cancelled) The
 * Act`, `The Act [ABGESAGT!]`, `VENUE ÄNDERUNG: Jazeek`) with its separator and brackets. Whole
 * words only: "Abgesagte Lesung" is a title, not a marker. A
 * "verschoben"/"verlegt" tail is [TITLE_STATUS_TAIL]'s, and a sentence that is the notice (Wild
 * at Heart) stays the title.
 */
private val TITLE_STATUS_MARKER =
    Regex(
        """^\s*[(\[]?\s*(?:abgesagt|absage|cancell?ed|venue\s?(?:ä|ae)nderung)\b!?\s*[)\]]?\s*[-–—:|]*\s*""" +
            """|\s*[-–—:|]*\s*[(\[]?\s*\b(?:abgesagt|absage|cancell?ed|venue\s?(?:ä|ae)nderung)!?\s*[)\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a [TITLE_STATUS_TAIL] and a leading or trailing [TITLE_STATUS_MARKER]; unchanged when
 * none or when stripping would leave nothing. It runs where the status has been read from the
 * same title, in [ScrapedEvent.toEventEntity], so no scraper can clean the status away first
 * (#2008).
 */
fun stripTitleStatusMarker(title: String): String {
    val stripped =
        title
            .replace(TITLE_STATUS_TAIL, "")
            .replace(TITLE_STATUS_MARKER, "")
            .replace(TITLE_NOISE_PATTERN, "")
            .trim()
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
 * A scheduling note a venue appends to a title: a "Nachholtermin vom <date>" / "(verschoben aus
 * <year>)" / "wird verschoben" / "Hochverlegung" note, or a "-verlegt ins <venue>-" / "-ins
 * <venue> verlegt-" suffix (Frannz's spellings of Metropol's prefix), anchored on "ins"/"nach"
 * after the word or on a leading dash before "ins". It carries the row's status, so only
 * [stripTitleStatusMarker] removes it, after [parseTitleStatus] has read it (#2008). The
 * title-level counterpart of [ARTIST_SUFFIX_PATTERN].
 */
private val TITLE_STATUS_TAIL =
    Regex(
        """\s+[-–—(]*\s*(?:nachholtermin|hochverlegung|(?:wird\s+)?verschoben|verlegt\s+(?:ins|nach))\b.*$""" +
            """|\s+[-–—(]+\s*ins?\s+\S.*?\s+verlegt!?\s*[-–—)]*\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Trailing noise that is no status: a "(ausverkauft)" / "-ausverkauft-" annotation, which would
 * split the act and its twin into two artists, and any stray trailing dash. Word- or
 * end-anchored, so "ausverkauften" mid-title is never touched.
 */
private val TITLE_NOISE_PATTERN =
    Regex(
        """\s+[-–—(]*\s*ausverkauft!?\s*[-–—)]?\s*$""" +
            """|\s+[-–—]\s*$""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips trailing noise and a stray dash: "Some Show -" to "Some Show". A scheduling note stays,
 * because it carries the status; [stripTitleStatusMarker] removes it once the status is read.
 * Unchanged when stripping would leave nothing.
 * Zero-width characters are removed and whitespace runs collapsed first: a line break inside
 * the heading or a double space in the CMS is presentation, not the name ("Adventurous Juan
 * (DJ-Set)", "Lucas Lauriente – Stand Up 2026"), and the tail patterns key on a single space. A
 * [ZERO_WIDTH] character reaches a title when an editor pastes one (MAAYA's "HOMECOMING DJ
 * WORKSHOP"); `\s` does not match it, so it is dropped. A backtick typed as an apostrophe is folded
 * ([foldTypedApostrophes]).
 */
fun cleanEventTitle(title: String): String {
    val collapsed =
        title
            .replace(ZERO_WIDTH, "")
            .trim()
            .replace(WHITESPACE_RUN, " ")
            .foldTypedApostrophes()
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
 * A free-entry phrase unambiguous enough for any text field: "Eintritt frei", "Eintritt: Frei",
 * "freier Eintritt", "bei freiem Eintritt", "kostenloser Eintritt", "Kostenfreie Tickets", "free
 * entry", "free admission", "Admission free". Multi-word, so it cannot collide with a band or
 * festival name or with a bare "kostenfrei (zzgl. Parkeintritt)", and whole-word, so "Eintritt
 * freiwillig" (pay what you want) is not free. [hasFreeEntryPhrase] applies it; a scraper that
 * only drops such a line from a description reads the pattern itself.
 */
val FREE_ENTRY_PHRASE =
    Regex(
        listOf(
            """eintritt\s*:?\s*frei""",
            """freie[mnr]?\s+eintritt""",
            """kostenlose[mnr]?\s+eintritt""",
            """kostenfreie[mnr]?\s+(?:eintritt|tickets?|karten)""",
            """free\s+(?:entry|admission)""",
            """admission\s*:?\s*free"""
        ).joinToString("|", prefix = """\b(?:""", postfix = """)\b"""),
        RegexOption.IGNORE_CASE
    )

/**
 * Single-word free markers, scanned only within [ScrapedEvent.priceNote], never the title, to
 * avoid "Freedom Festival" or "Freikörperkultur". Word-boundary matched, so "free" is not
 * "freestyle".
 */
private val FREE_TOKENS = listOf("free", "frei", "gratis", "kostenlos", "umsonst")

private val FREE_TOKEN_PATTERN =
    Regex("""\b(${FREE_TOKENS.joinToString("|") { Regex.escape(it) }})\b""", RegexOption.IGNORE_CASE)

/**
 * A free-entry marker limited to the start of the night: "free entry until midnight", "FREE ENTRY
 * TILL 00:30", "Freier Eintritt für Ladies bis 0 Uhr". Everyone arriving later pays, so the night
 * is not free, and [detectFree] removes such a span before it looks for a marker.
 */
private val TIME_LIMITED_FREE =
    Regex(
        """\b(?:free|frei(?:e[nr]?)?|gratis|kostenlos|umsonst)\b(?:\s+[\p{L}&]+){0,4}\s+(?:until|till?|bis|before|vor)\s+(?:\d|midnight|mitternacht)""",
        RegexOption.IGNORE_CASE
    )

/**
 * Whether [text] states free entry ([FREE_ENTRY_PHRASE]) for the whole night. A
 * [TIME_LIMITED_FREE] offer ("free entry for ladies until 0 Uhr") is removed first: everyone
 * arriving later pays.
 */
fun hasFreeEntryPhrase(text: String?): Boolean = text != null && FREE_ENTRY_PHRASE.containsMatchIn(text.replace(TIME_LIMITED_FREE, " "))

/**
 * Whether an event is free. A positive signal is required, since an absent price is unknown,
 * not free: an explicit €0 price, a [hasFreeEntryPhrase] match in the title or price note, or a
 * [FREE_TOKENS] match in the price note. A [TIME_LIMITED_FREE] marker is not a signal. The price
 * note counts only when no positive price was parsed: beside a price, "free for transgender women"
 * or "Kinder frei" is a concession for one group, not free entry (#2789).
 */
fun detectFree(
    pricePresale: BigDecimal? = null,
    priceBoxOffice: BigDecimal? = null,
    priceNote: String? = null,
    title: String? = null
): Boolean {
    val hasZeroPrice = pricePresale?.signum() == 0 || priceBoxOffice?.signum() == 0
    val tokenInNote = priceNote?.replace(TIME_LIMITED_FREE, " ")?.let { FREE_TOKEN_PATTERN.containsMatchIn(it) } ?: false
    val hasPositivePrice = pricePresale?.signum() == 1 || priceBoxOffice?.signum() == 1
    return hasZeroPrice || hasFreeEntryPhrase(title) || (!hasPositivePrice && (hasFreeEntryPhrase(priceNote) || tokenInNote))
}

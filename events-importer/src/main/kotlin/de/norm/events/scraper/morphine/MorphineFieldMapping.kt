package de.norm.events.scraper.morphine

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.parseEnglishWeekday
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.parseTime
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

// Field mapping shared by the Morphine Raum overview and detail scrapers: the venue's "Live
// Recording" billing framing, the one-line `.block.day` header, and the free-text pricing line
// in `.block.priceevent`. Both pages state the date as `DD.MM.YY` and are read the same way, so
// the overview's fallback stays usable when a detail fetch fails. Every case is asserted in
// MorphineFieldMappingTest, where the examples live.

/**
 * Morphine's format tails, "… - Live Recording", "… Listening Session" and a bare "… Live" after
 * the work ("Evelyn Plaschg - Alphabet Live"), stripped from a **derived artist name** only.
 *
 * The room records most concerts for the label and bills that in both the title and the lineup
 * entry ("Invisible Weather - Live Recording", "Raphael Rogiński – Qırım - Live Recording"). It
 * describes what the night *is*, not who plays, so it must not enter an artist name — but it is
 * part of the published name, so the stored [title][de.norm.events.scraper.ScrapedEvent.title]
 * keeps it.
 *
 * Every dash spelling is accepted (`-`, `–`, `—`), as is a bare space, and the tail is
 * end-anchored so an act whose name contains the words mid-string is untouched. A bare `Live` needs
 * the space or the dash before it, so `Olive` keeps its name.
 */
private val FORMAT_TAIL =
    Regex("""(?:\s*[-–—]?\s*(?:live\s+recording|listening\s+session)|(?:\s*[-–—]\s*|\s+)live)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Strips a trailing [FORMAT_TAIL] from a billed name; unchanged without such a tail or when
 * stripping would leave nothing.
 */
internal fun stripFormatTail(name: String): String {
    val stripped = name.trim().replace(FORMAT_TAIL, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * The billing a title names its acts in. A title with a [FORMAT_TAIL] bills `<act> – <work> –
 * <format>`, so a dash left after the tail comes off leads into the work, and the act is the head:
 * `Gibrana Cervantes — verdad y error Listening Session` is Gibrana Cervantes (#2268). A title
 * without the tail keeps its dash for #302's sync-time head rule, which knows whether the head is
 * an act.
 */
internal fun titleBilling(title: String): String {
    val stripped = stripFormatTail(title)
    return if (stripped == title.trim()) {
        stripped
    } else {
        stripped
            .split(DASH, limit = 2)
            .first()
            .trim()
            .ifBlank { stripped }
    }
}

/** A dash between two billed segments, padded so a hyphenated name stays whole. */
internal val DASH = Regex("""\s+[-–—]\s+""")

/**
 * The `DD.MM.YY` date inside the `.block.day` header (`"Friday, 07.08.26, door  20:00"`). The
 * weekday and door time around it are prose, so the date is matched, not split by position.
 */
private val DAY_LINE_DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{2}\b""")

/** The weekday the `.block.day` header line opens with, in English: `"Friday"`. */
private val DAY_LINE_WEEKDAY = Regex("""^\s*(\p{L}+),""")

/** The event date from a `.block.day` header line, checked against its weekday ([dateCheckedAgainstWeekday]), or `null`. */
internal fun parseDayLineDate(dayLine: String?): LocalDate? {
    val line = dayLine?.trim().orEmpty()
    return parseGermanShortDate(DAY_LINE_DATE.find(line)?.value)?.let {
        dateCheckedAgainstWeekday(it, parseEnglishWeekday(DAY_LINE_WEEKDAY.find(line)?.groupValues?.get(1)), line)
    }
}

/** The venue's own door label in the `.block.day` header line: `door`, never `Einlass`. */
private const val DAY_LINE_DOORS_LABEL = "doors?"

/** The door time from a `.block.day` header line, or `null`. */
internal fun parseDayLineDoors(dayLine: String?): LocalTime? = labelledClock(dayLine, DAY_LINE_DOORS_LABEL)

/** `Listening session + intervention: 19:30`: a line that is a label, a colon and one clock. */
private val LABELLED_LINE_CLOCK = Regex("""^(.{1,60}?)\s*:\s*(\d{1,2})[:.](\d{2})\s*(?:uhr|h)?$""", RegexOption.IGNORE_CASE)

/** A label that names the doors or the end, so its clock is not the start. */
private val NOT_A_START_LABEL = Regex("""(?<!\p{L})(?:$DOORS_LABELS|end|ende|until|bis|curfew|close)(?!\p{L})""", RegexOption.IGNORE_CASE)

/**
 * The start the programme text labels with the night's format, or `null` (#2419).
 *
 * The venue repeats the day line in the prose and puts the start on the line under it:
 * `Friday, 02.10.2026, doors 19:00` then `Listening session + intervention: 19:30`. Only the line
 * right after a doors line is read, so a clock elsewhere in the prose is never the start.
 */
internal fun parseProgrammeStart(lines: List<String>): LocalTime? {
    val texts = lines.map { it.trim() }.filter { it.isNotBlank() }
    val doorsLine = texts.indexOfFirst { labelledClock(it, DOORS_LABELS) != null }
    val (label, hour, minute) =
        texts
            .getOrNull(doorsLine + 1)
            ?.takeIf { doorsLine >= 0 }
            ?.let { LABELLED_LINE_CLOCK.matchEntire(it) }
            ?.destructured ?: return null
    return if (NOT_A_START_LABEL.containsMatchIn(label)) null else parseClock("$hour:$minute")
}

/**
 * Markers identifying the first `.block.priceevent` paragraph as a **pricing** line.
 *
 * That box is free text above the venue's address and does not always hold a price — one show
 * uses it for a house rule ("Concert (two sets!) starts at 20:00 sharp."). Storing that as a
 * [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] would label prose as pricing, and
 * [parseDoorPrice] would read its "20:00" as an amount, so a signal is required first.
 *
 * `Eu` sits beside `Euro` because the venue abbreviates it so ("10 - 15 Eu Sliding scale
 * Donation at the door."); word-anchored so it cannot match inside another word.
 */
private val PRICE_MARKER =
    Regex("""€|\beur\b|\beuros?\b|\beu\b|donation|spende|sliding\s+scale|free|frei""", RegexOption.IGNORE_CASE)

/**
 * The `.block.priceevent` [text] when it reads as a pricing line ([PRICE_MARKER]), or `null`
 * when blank or without a pricing signal.
 */
internal fun readPriceNote(text: String?): String? = text?.trim()?.takeIf { it.isNotBlank() && PRICE_MARKER.containsMatchIn(it) }

/** Any amount in a pricing line — an integer with an optional two-digit decimal part. */
private val PRICE_AMOUNT = Regex("""\d+(?:[.,]\d{1,2})?""")

/**
 * A single door price from a pricing [note], or `null` unless stated unambiguously.
 *
 * Morphine prices almost every night as a **range** — "10 - 15 Euro donation", "€10-15 on the
 * door", "Sliding scale 20- 25 Euro at The Door" — which the model has no field for, so those
 * stay in the [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] verbatim rather than
 * flattened onto one bound. Only a note with exactly **one distinct** amount ("10 Euro At The
 * Door") yields a [priceBoxOffice][de.norm.events.scraper.ScrapedEvent.priceBoxOffice]. Any
 * second number — a range bound, a set count — is ambiguous and returns `null`: no price rather
 * than a wrong one.
 */
internal fun parseDoorPrice(note: String?): BigDecimal? {
    if (note.isNullOrBlank()) return null
    val amounts =
        PRICE_AMOUNT
            .findAll(note)
            .map { it.value.replace(',', '.') }
            .mapNotNull { it.toBigDecimalOrNull() }
            .distinct()
            .toList()
    return amounts.singleOrNull()
}

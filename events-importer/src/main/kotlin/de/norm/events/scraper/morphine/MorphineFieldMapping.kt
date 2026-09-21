package de.norm.events.scraper.morphine

import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stripArtistSuffix
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

// Field mapping shared by the Morphine Raum overview and detail scrapers: the venue's "Live
// Recording" billing framing, the one-line `.block.day` header, and the free-text pricing line
// in `.block.priceevent`. Both pages state the date as `DD.MM.YY` and are read the same way, so
// the overview's fallback stays usable when a detail fetch fails. Every case is asserted in
// MorphineFieldMappingTest, where the examples live.

/**
 * Morphine's "… - Live Recording" billing framing, stripped from a **derived artist name** only.
 *
 * The room records most concerts for the label and bills that in both the title and the lineup
 * entry ("Invisible Weather - Live Recording", "Raphael Rogiński – Qırım - Live Recording"). It
 * describes what the night *is*, not who plays, so it must not enter an artist name — but it is
 * part of the published name, so the stored [title][de.norm.events.scraper.ScrapedEvent.title]
 * keeps it.
 *
 * Every dash spelling is accepted (`-`, `–`, `—`), as is a bare space, and the tail is
 * end-anchored so an act whose name contains the words mid-string is untouched.
 */
private val LIVE_RECORDING_SUFFIX = Regex("""\s*[-–—]?\s*live\s+recording\s*$""", RegexOption.IGNORE_CASE)

/**
 * Strips a trailing [LIVE_RECORDING_SUFFIX] from a billed name; unchanged without such a tail
 * or when stripping would leave nothing.
 */
fun stripLiveRecordingSuffix(name: String): String {
    val stripped = name.trim().replace(LIVE_RECORDING_SUFFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * The billed names on a `ul.lineup` set line, for the four shapes the venue writes beside a bare
 * name (#1674): a `<promoter> presents:` frame comes off the front, a `performs with <instrument>`
 * tail off the back, a ` - ` list is split when every segment is short enough to be an act (each
 * may carry a `Live`) and there are three or more of them, a pair whose one segment equals the
 * event [title] is a film or a work beside its performer, and a ` - ` tail that lists members
 * (`PICI - A & B`) keeps the head. A pair of names (`Alister Spence – Within Without`) stays glued
 * for the sync-time head rule of #302, which knows whether the head is an act. Each name then goes
 * through [stripArtistSuffix]; the caller bills it through `headlinersFromTitle`, whose co-bill
 * split is what a member list must not reach.
 */
fun morphineSetLineActs(
    line: String,
    title: String
): List<String> {
    val framed = line.replace(PRESENTS_FRAME, "").replace(PERFORMS_WITH_TAIL, "").trim()
    val segments = framed.split(DASH).map { it.trim() }.filter { it.isNotBlank() }
    val names =
        when {
            segments.size == 2 && isMemberList(segments[0], segments[1]) -> segments.take(1)
            segments.size == 2 && segments.any { it.equals(title, ignoreCase = true) } -> segments
            segments.size >= MIN_DASH_LIST && segments.all { it.split(WHITESPACE).size <= MAX_ACT_WORDS } -> segments
            else -> listOf(framed)
        }
    // The title is a film or a work only beside another name; a night titled after its one act keeps it.
    return names
        .filterNot { names.size > 1 && it.equals(title, ignoreCase = true) }
        .map { stripArtistSuffix(it) }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
}

/** `PICI - Clémence Manachère & Polina Pohozha`: a one-token head and a conjunction in the tail. */
private fun isMemberList(
    head: String,
    tail: String
): Boolean = !head.contains(WHITESPACE) && MEMBER_JOIN.containsMatchIn(tail)

/** `Uncanny Valley presents:` — the frame the venue puts before a guest promoter's bill. */
private val PRESENTS_FRAME = Regex("""^.{2,60}?\s+(?:presents|präsentiert|pres\.?)\s*:?\s+""", RegexOption.IGNORE_CASE)

/** `Jakob Vasak performs with the Kobophon` — the instrument is not a co-act. */
private val PERFORMS_WITH_TAIL = Regex("""\s+performs\s+(?:with|on)\s+.*$""", RegexOption.IGNORE_CASE)

private val DASH = Regex("""\s+[-–—]\s+""")
private val WHITESPACE = Regex("""\s+""")
private val MEMBER_JOIN = Regex("""\s*(?:&|,|\band\b|\bund\b)\s*""", RegexOption.IGNORE_CASE)

/** An act billed on a set line is a name, not a sentence: four words plus a `Live`. */
private const val MAX_ACT_WORDS = 5

/** A dash list of acts has at least three; a pair is an act and its work until #302's rule says otherwise. */
private const val MIN_DASH_LIST = 3

/**
 * The `DD.MM.YY` date inside the `.block.day` header (`"Friday, 07.08.26, door  20:00"`). The
 * weekday and door time around it are prose, so the date is matched, not split by position.
 */
private val DAY_LINE_DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{2}\b""")

/** The door time in the same header line — the venue labels it `door`, never `Einlass`. */
private val DAY_LINE_DOORS = Regex("""doors?\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

/** The event date from a `.block.day` header line, or `null`. */
fun parseDayLineDate(dayLine: String?): LocalDate? = parseGermanShortDate(dayLine?.let { DAY_LINE_DATE.find(it)?.value })

/** The door time from a `.block.day` header line, or `null`. */
fun parseDayLineDoors(dayLine: String?): LocalTime? = parseTime(dayLine?.let { DAY_LINE_DOORS.find(it)?.groupValues?.get(1) })

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
fun readPriceNote(text: String?): String? = text?.trim()?.takeIf { it.isNotBlank() && PRICE_MARKER.containsMatchIn(it) }

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
fun parseDoorPrice(note: String?): BigDecimal? {
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

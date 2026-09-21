package de.norm.events.scraper.morphine

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

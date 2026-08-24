package de.norm.events.scraper.morphine

import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.parseTime
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

// Field mapping shared by the Morphine Raum overview and detail scrapers: the venue's own "Live
// Recording" billing framing, the one-line `.block.day` header, and the free-text pricing line in
// `.block.priceevent`. Both pages state the date in the same `DD.MM.YY` spelling and are read the
// same way, so the overview's fallback data stays usable when a detail-page fetch fails.

/**
 * Morphine's own "… - Live Recording" billing framing, stripped from a **derived artist name**
 * only.
 *
 * The room records most of its concerts for the label, and bills that fact in both the event
 * title and the lineup entry ("Invisible Weather - Live Recording", "Raphael Rogiński – Qırım -
 * Live Recording"). It describes what the night *is*, not who plays, so it must not become part
 * of an artist name — but it is part of the event's published name, so the stored
 * [title][de.norm.events.scraper.ScrapedEvent.title] keeps it.
 *
 * Every dash spelling the venue uses is accepted (`-`, `–`, `—`), as is a bare space, and the
 * tail is end-anchored so an act whose own name contains the words mid-string is untouched.
 */
private val LIVE_RECORDING_SUFFIX = Regex("""\s*[-–—]?\s*live\s+recording\s*$""", RegexOption.IGNORE_CASE)

/**
 * Strips a trailing [LIVE_RECORDING_SUFFIX] from a billed name, keeping the input unchanged when
 * there is no such tail or when stripping would leave nothing.
 *
 * Example: `"Invisible Weather - Live Recording"` → `"Invisible Weather"`; `"VINYL REDUCTION"` is unchanged.
 */
fun stripLiveRecordingSuffix(name: String): String {
    val stripped = name.trim().replace(LIVE_RECORDING_SUFFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * The `DD.MM.YY` date inside the detail page's `.block.day` header line
 * (`"Friday, 07.08.26, door  20:00"`). The weekday and the door time around it are prose, so the
 * date is matched rather than split out by position.
 */
private val DAY_LINE_DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{2}\b""")

/** The door time in the same header line — the venue labels it `door`, never `Einlass`. */
private val DAY_LINE_DOORS = Regex("""doors?\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

/**
 * Reads the event date from a `.block.day` header line, or `null` when the line carries none.
 *
 * Example: `"Friday, 07.08.26, door  20:00"` → `2026-08-07`.
 */
fun parseDayLineDate(dayLine: String?): LocalDate? = parseGermanShortDate(dayLine?.let { DAY_LINE_DATE.find(it)?.value })

/**
 * Reads the door time from a `.block.day` header line, or `null` when the line carries none.
 *
 * Example: `"Friday, 07.08.26, door  20:00"` → `20:00`.
 */
fun parseDayLineDoors(dayLine: String?): LocalTime? = parseTime(dayLine?.let { DAY_LINE_DOORS.find(it)?.groupValues?.get(1) })

/**
 * Markers that identify the first paragraph of the `.block.priceevent` box as a **pricing** line.
 *
 * That box is a free-text field above the venue's address, and does not always hold a price — one
 * show uses it for a house rule ("Concert (two sets!) starts at 20:00 sharp."). Storing that as a
 * [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] would label prose as pricing, and its
 * "20:00" would be read as an amount by [parseDoorPrice], so a pricing signal is required first.
 *
 * `Eu` is listed beside `Euro` because the venue abbreviates it that way ("10 - 15 Eu Sliding
 * scale Donation at the door."); it is word-anchored so it cannot match inside another word.
 */
private val PRICE_MARKER =
    Regex("""€|\beur\b|\beuros?\b|\beu\b|donation|spende|sliding\s+scale|free|frei""", RegexOption.IGNORE_CASE)

/**
 * Returns the `.block.priceevent` [text] when it reads as a pricing line ([PRICE_MARKER]), or
 * `null` when it is blank or carries no pricing signal at all.
 *
 * Example: `"10 - 15 Euro donation"` is kept; `"Concert starts at 20:00 sharp."` yields `null`.
 */
fun readPriceNote(text: String?): String? = text?.trim()?.takeIf { it.isNotBlank() && PRICE_MARKER.containsMatchIn(it) }

/** Any amount written in a pricing line — an integer with an optional two-digit decimal part. */
private val PRICE_AMOUNT = Regex("""\d+(?:[.,]\d{1,2})?""")

/**
 * Reads a single door price out of a pricing [note], or `null` when the note does not state one
 * unambiguously.
 *
 * Morphine prices almost every night as a **range** — "10 - 15 Euro donation", "€10-15 on the
 * door", "Sliding scale 20- 25 Euro at The Door" — which the data model has no field for, so those
 * are left to the [priceNote][de.norm.events.scraper.ScrapedEvent.priceNote] verbatim rather than
 * flattened onto one of their bounds. Only a note stating exactly **one distinct** amount ("10 Euro
 * At The Door") yields a
 * [priceBoxOffice][de.norm.events.scraper.ScrapedEvent.priceBoxOffice]. A note carrying any second
 * number — a range bound, a set count — is ambiguous and returns `null`, so the rule errs toward
 * storing no price rather than a wrong one.
 *
 * Example: `"10 Euro At The Door"` → `10`; `"10 - 15 Euro donation"` → `null`, being a range.
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

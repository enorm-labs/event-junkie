package de.norm.events.scraper

// The sold-out marker a venue writes into a title or subtitle, read once for every venue (#2103).
// A structural signal — a CSS class, a stock field, a sold-out button — stays with its scraper.

/**
 * The words: `sold out` / `sold-out` / `soldout`, `ausverkauft`, `ausgebucht`, and `ausverauft`, the
 * misspelling Schokoladen prints. Letter-bounded, so "ausverkauften" and "Soldouts" are not one.
 */
private const val SOLD_OUT_WORD = """(?<!\p{L})(?:sold[\s-]?out|ausverkauft|ausverauft|ausgebucht)(?!\p{L})"""

/**
 * The shapes a marker takes, each anchored so a name or a sentence about a record cannot match:
 * - a prefix wrapped or set off: `!!AUSVERKAUFT!! Tim Vantol`, `(sold out) X`, `SOLD OUT - Otha`;
 * - a bracketed note, alone or leading a note: `[ausverkauft]`, `(SOLD OUT!!!)`, `[pre-sale sold out, 10 tix
 *   on the doors]`, but not `(Sold Out Tour)`;
 * - a tail: `Haken -ausverkauft-`, `X - ausverkauft`, `Flower Face SOLD OUT`, or wrapped in dashes and glued
 *   to the title: `Mitsing-Event-ausverkauft-`;
 * - a notice sentence: `Das Konzert ist restlos ausverkauft`, `this show is sold out`.
 */
private val SOLD_OUT_MARKER =
    Regex(
        listOf(
            """^\s*(?:!+\s*$SOLD_OUT_WORD\s*!+|[(\[]\s*$SOLD_OUT_WORD\s*!*\s*[)\]]|$SOLD_OUT_WORD\s*!*\s*[-–—:|])\s*[-–—:|]*\s*""",
            """\s*[(\[]\s*(?:[^()\[\]]*?[\s,;:/-])?$SOLD_OUT_WORD(?:\s*[!.,;:/-][^()\[\]]*)?\s*!*\s*[)\]]""",
            """(?:\s+[-–—|]+\s*|\s+)$SOLD_OUT_WORD\s*!*\s*[-–—]*\s*$""",
            """[-–—]+$SOLD_OUT_WORD\s*!*\s*[-–—]+\s*$""",
            """\b(?:ist|sind|is|are)\s+(?:(?:restlos|komplett|leider|bereits|completely|now)\s+)*$SOLD_OUT_WORD"""
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

/** Whether [text] carries a sold-out marker in one of the [SOLD_OUT_MARKER] shapes. */
fun hasSoldOutMarker(text: String?): Boolean = text != null && SOLD_OUT_MARKER.containsMatchIn(text)

/**
 * [text] without its sold-out marker, for the stored title and the artist input: `!!AUSVERKAUFT!! Tim
 * Vantol` bills Tim Vantol, `X [ausverkauft]` bills X. Unchanged when stripping would leave nothing.
 */
fun stripSoldOutMarker(text: String): String =
    text
        .replace(SOLD_OUT_MARKER, " ")
        .trim()
        .replace(MULTI_SPACE, " ")
        .ifBlank { text.trim() }

private val MULTI_SPACE = Regex("""\s{2,}""")

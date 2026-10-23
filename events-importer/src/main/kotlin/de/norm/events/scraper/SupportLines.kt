package de.norm.events.scraper

// Support acts read from a subtitle's `Support:` / `Opener:` line. Part of the artist-name rules
// whose entry point is buildArtistList in ArtistNameMapping.kt.

/**
 * Extracts support act names from a subtitle's `"… + <marker>: A & B"` pattern, where
 * `<marker>` is `Support`, `Support Act`, `Opener`, `Opening Act` or `Special Guest(s)`
 * ([SUPPORT_INTRO_PATTERN]). Every line is read, not only the first: a venue that stacks
 * `Support Act: Jinjer` and `Opening Act: Dying Wish` on separate lines bills two acts (#1680).
 * A line stacking both markers instead — `Support: Neck Deep Opener: Charlotte Sands` — is split
 * at the second marker ([FURTHER_SUPPORT_MARKER]), which needs its colon, so the band
 * `Support Lesbiens` billed as a support act keeps its name (#1730).
 * Empty when no support line. Shared by Privatclub, Astra, Hole 44 and others.
 */
fun extractSupportFromSubtitle(subtitle: String?): List<String> =
    subtitle
        .orEmpty()
        .split(SUBTITLE_LINE_SEPARATOR)
        .mapNotNull { line -> SUPPORT_INTRO_PATTERN.find(line)?.groupValues?.get(1) }
        .flatMap { tail -> tail.split(FURTHER_SUPPORT_MARKER) }
        .flatMap(::splitSupportActs)
        .map { it.replaceFirst(ROLE_LABEL_PREFIX, "").trim() }
        .filter { it.isNotBlank() }

/**
 * The words that bill a support act, as a regex alternation for a scraper's own pattern:
 * `Support`, `div. Support`, `Support Act`, `Opener`, `Opening Act`, `Special Guest`, `Vorband`.
 * A bare `Opening` is left out: `Opening: <exhibition>` is how a gallery bills a vernissage.
 * Each pattern that uses it decides whether a colon must follow. The optional `Act` is how
 * Velomax spells the markers (#1680).
 */
const val SUPPORT_LABELS =
    """div\.?\s*supports?|(?:supports?|openers?|special\s+guests?)(?:\s+acts?)?|opening\s+acts?|vorbands?"""

/** A support-billing marker and its colon: `Support:`, `Support Act:`, `Opener:`, `Special Guest:`. */
private const val SUPPORT_MARKER = """(?:$SUPPORT_LABELS)\s*:\s*"""

/** The first support-billing marker in one subtitle line, capturing the acts after it to the end of that line. */
private val SUPPORT_INTRO_PATTERN = Regex("""$SUPPORT_MARKER(.+)""", RegexOption.IGNORE_CASE)

/**
 * A second marker inside that capture, which separates two billings a venue wrote on one line.
 * The leading whitespace keeps it from matching the marker the capture already began after.
 */
private val FURTHER_SUPPORT_MARKER = Regex("""\s+$SUPPORT_MARKER""", RegexOption.IGNORE_CASE)

/**
 * The subtitle lines carrying a support-billing marker from already-split [lines], joined with a
 * newline, or `null` when none does. A line without a marker, such as an "ABGESAGT …" note, is
 * dropped, or `.text()` flattens it into a support act. Pair with
 * [textLinesAt][de.norm.events.scraper.textLinesAt]. Shared by Astra and Lido.
 */
fun supportSubtitleLines(lines: List<String>): String? = lines.filter { SUPPORT_INTRO_PATTERN.containsMatchIn(it) }.joinToString("\n").ifEmpty { null }

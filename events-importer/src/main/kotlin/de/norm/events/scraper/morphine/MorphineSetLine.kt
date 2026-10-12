package de.norm.events.scraper.morphine

import de.norm.events.scraper.PRESENTS_VERBS
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.stripArtistSuffix

// How a Morphine Raum `ul.lineup` set line becomes its acts. Every case is asserted in
// MorphineFieldMappingTest.

/**
 * The billed names on a `ul.lineup` set line, for the four shapes the venue writes beside a bare
 * name (#1674): a `<promoter> presents:` frame comes off the front, a `performs with <instrument>`
 * tail off the back, a ` - ` list is split when every segment is short enough to be an act (each
 * may carry a `Live`) and there are three or more of them, a pair whose one segment equals the
 * event [title] is a film or a work beside its performer, and a ` - ` tail that lists members
 * (`PICI - A & B`), dates a work ([WORK_MARKER]) or is left out of a title that bills the head
 * (`Maurice Louca & Ayman Asfour - FERA`, #2778) keeps the head. A line that repeats a title with a
 * format tail bills the title's head ([formatTitleHeadActs], #3163). A list whose dashes differ
 * (`Antonio Raia - Σ [sigma] — Berlin Series I`) is an act and a subtitled work, so it keeps the
 * head. Any other pair of names stays glued for the sync-time head rule of #302, which knows whether
 * the head is an act. Each name then goes through [stripArtistSuffix]; the caller bills it through
 * `headlinersFromTitle`, whose co-bill split is what a member list must not reach.
 */
internal fun morphineSetLineActs(
    line: String,
    title: String
): List<String> {
    val framed = line.replace(PRESENTS_FRAME, "").replace(PERFORMS_WITH_TAIL, "").trim()
    val segments = framed.split(DASH).map { it.trim() }.filter { it.isNotBlank() }
    val separators = DASH.findAll(framed).map { it.value.trim() }.toSet()
    val names =
        formatTitleHeadActs(framed, title) ?: when {
            segments.size >= MIN_DASH_LIST && separators.size > 1 -> segments.take(1)
            segments.size == 2 -> pairActs(segments[0], segments[1], title) ?: listOf(framed)
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

/** The acts of a ` - ` pair when a work marker, a member list or the [title] decides them, or `null`. */
private fun pairActs(
    head: String,
    tail: String,
    title: String
): List<String>? =
    when {
        WORK_MARKER.containsMatchIn(tail) -> listOf(head)
        isMemberList(head, tail) -> listOf(head)
        head.equals(title, ignoreCase = true) || tail.equals(title, ignoreCase = true) -> listOf(head, tail)
        billsInTitle(head, title) && !billsInTitle(tail, title) -> listOf(head)
        else -> null
    }

/**
 * The acts of a set [line] that repeats a [title] without its [FORMAT_TAIL], or `null`.
 *
 * `Fred Frith/Verena Marisa – Murmurations – Live Recording` bills `<act> – <work> – <format>`, as
 * [titleBilling] reads it, so the line `Fred Frith/Verena Marisa – Murmurations` names the same act
 * and work. A ` | ` in the title counts as a dash. The head splits at a `/` only between names of
 * two or more words, so `AC/DC` stays one act.
 */
private fun formatTitleHeadActs(
    line: String,
    title: String
): List<String>? {
    val stripped = stripFormatTail(title)
    val titleSegments = stripped.split(DASH_OR_PIPE).map { it.trim().lowercase() }
    val lineSegments = line.split(DASH_OR_PIPE).map { it.trim().lowercase() }
    if (stripped == title.trim() || titleSegments.size != 2 || titleSegments != lineSegments) return null
    val head = line.split(DASH_OR_PIPE).first().trim()
    val acts = head.split(SLASH).map { it.trim() }
    return if (acts.size > 1 && acts.all { it.split(WHITESPACE).size > 1 }) acts else listOf(head)
}

/**
 * `PICI - Clémence Manachère & Polina Pohozha`, `Exoteric Chamber - Gilles Aubry & Marta
 * Zapparoli`: an act and the people in it, which bills the act alone.
 *
 * Two guards keep a co-bill out. The tail must name **two or more** people, so
 * `Alister Spence – Within Without` stays a pair for #302's sync-time head rule. The head must
 * carry no conjunction of its own, so `Tony Buck & Flo Stoffner - Live Recording` stays two acts.
 * With both in place the head may be two words: #1674 allowed one, and the venue's duos are
 * named `Exoteric Chamber` as often as `PICI` (#1710). Three words waits for a line that asks.
 */
private fun isMemberList(
    head: String,
    tail: String
): Boolean =
    head.split(WHITESPACE).size <= MAX_MEMBER_HEAD_WORDS &&
        !MEMBER_JOIN.containsMatchIn(head) &&
        tail.split(MEMBER_JOIN).count { it.isNotBlank() } >= MIN_MEMBERS

/**
 * Whether the event [title] bills [name] as whole words, ignoring case. The title is the venue's own
 * billing of the night, so a dash tail it leaves out beside a head it names is a work, not an act:
 * `Maurice Louca & Ayman Asfour - FERA` under `BNNT & Radwan Ghazi Moumneh / Maurice Louca & Ayman
 * Asfour` is an album (#2778).
 */
private fun billsInTitle(
    name: String,
    title: String
): Boolean = Regex("""(?<![\p{L}\p{N}])${Regex.escape(name)}(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE).containsMatchIn(title)

/** `Uncanny Valley presents:` — the frame the venue puts before a guest promoter's bill. */
private val PRESENTS_FRAME = Regex("""^.{2,60}?\s+(?:$PRESENTS_VERBS|pres\.?)\s*:?\s+""", RegexOption.IGNORE_CASE)

/** `Jakob Vasak performs with the Kobophon` — the instrument is not a co-act. */
private val PERFORMS_WITH_TAIL = Regex("""\s+performs\s+(?:with|on)\s+.*$""", RegexOption.IGNORE_CASE)

/**
 * `Repeater, Quietus (2024), 35’ For 8 percussion instruments`: a dash tail with a bracketed year
 * or a duration in minutes is a work, so the head is the act (#2705). Neither appears in an act's name.
 */
private val WORK_MARKER = Regex("""\((?:19|20)\d{2}\)|\b\d+\s?[’'′]""")

private val DASH_OR_PIPE = Regex("""\s+[-–—|]\s+""")
private val SLASH = Regex("""\s*/\s*""")
private val MEMBER_JOIN = Regex("""\s*(?:&|,|\band\b|\bund\b)\s*""", RegexOption.IGNORE_CASE)

/** An act billed on a set line is a name, not a sentence: four words plus a `Live`. */
private const val MAX_ACT_WORDS = 5

/** A dash list of acts has at least three; a pair is an act and its work until #302's rule says otherwise. */
private const val MIN_DASH_LIST = 3

/** A member list names at least two people; one name after the dash is a work, not a line-up. */
private const val MIN_MEMBERS = 2

/** How many words an act can have and still be read as the head of its own member list. */
private const val MAX_MEMBER_HEAD_WORDS = 2

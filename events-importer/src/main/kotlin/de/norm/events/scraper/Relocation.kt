package de.norm.events.scraper

import de.norm.events.common.deshoutWord
import de.norm.events.common.isShortInitialism
import de.norm.events.event.EventStatus
import java.time.LocalDate

// Which end of a move a `RELOCATED` or `POSTPONED` row is, read from the venue's own note (#1551, ADR-030).
// The badge-to-status mapping stays in EventFieldMapping.kt.

/**
 * The two houses a relocation note names, as written but de-shouted: [from] after "vom"/"aus dem"/"from",
 * [to] after "ins"/"in den"/"nach"/"to". Either may be missing — Badehaus writes only
 * `Verlegt ins Mikropol`, Gretchen only `verlegt vom Frannz`, Columbia Theater neither.
 */
data class Relocation(
    val from: String?,
    val to: String?
)

/**
 * Reads the relocation a status note, title or description states, or `null` when the text
 * carries no relocation word at all.
 *
 * The same sentence sits on both ends of a move — Huxleys and Hole 44 both print "Die Show wird
 * vom Huxleys ins Hole44 verlegt" — so this reads the two names and leaves the direction to
 * [resolveRelocation], which knows the row's own venue (#1551). A name runs to the next
 * delimiter, and a leading article is dropped: "in den Festsaal Kreuzberg" names `Festsaal
 * Kreuzberg`.
 */
fun parseRelocation(text: String): Relocation? {
    // A tour name in quotes is not a house: "„Soul to SØL World Tour 2026“ HOCHVERLEGT IN DAS COLUMBIA THEATER".
    val unquoted = text.replace(QUOTED, " ")
    if (!RELOCATION_WORD.containsMatchIn(unquoted)) {
        return MOVED_HERE_INSTEAD.find(unquoted)?.let { Relocation(from = null, to = venueName(it.groupValues[1])) }
    }
    val to = RELOCATION_TO.find(unquoted) ?: RELOCATION_TO_BARE.find(unquoted)
    return Relocation(
        from = RELOCATION_FROM.find(unquoted)?.let { venueName(it.groupValues[1]) },
        to = to?.let { venueName(it.groupValues[1]) }
    )
}

/**
 * Decides what a `RELOCATED` badge means for the row at [venueSlug]: moved away, or arrived.
 *
 * Returns the status to store and the destination as [parseRelocation] read it. A destination that is
 * another house makes this the origin — `RELOCATED`, with the name; a destination that is this
 * house, or an origin that is another, makes this the destination — `SCHEDULED`, the show
 * happens here. A note naming nothing keeps `RELOCATED` and no destination. A status other
 * than `RELOCATED` passes through untouched, [relocation] or not.
 */
fun resolveRelocation(
    status: String,
    relocation: Relocation?,
    venueSlug: String
): Pair<String, String?> {
    if (status != EventStatus.RELOCATED.name) return status to null
    val here = compactName(venueSlug)
    val to = relocation?.to?.takeUnless { namesSameHouse(compactName(it), here) }
    val arrived =
        relocation?.to?.let { namesSameHouse(compactName(it), here) } == true ||
            (relocation?.to == null && relocation?.from?.let { !namesSameHouse(compactName(it), here) } == true)
    return when {
        to != null -> EventStatus.RELOCATED.name to to
        arrived -> EventStatus.SCHEDULED.name to null
        else -> EventStatus.RELOCATED.name to null
    }
}

/**
 * Decides what a `POSTPONED` status means for the row dated [eventDate]: the date the show left,
 * or the date it moved to.
 *
 * Huxleys prints "vom 06.03.2026 auf den 01.10.2026 verschoben" on the new date's row. A note
 * that names [eventDate] after "auf" makes this row the replacement, so the show happens here:
 * `SCHEDULED`. Any other status, or notes naming another date or none, pass through (#2206).
 */
fun resolvePostponement(
    status: String,
    notes: List<String>,
    eventDate: LocalDate
): String {
    if (status != EventStatus.POSTPONED.name) return status
    val movedHere = notes.any { note -> POSTPONED_TO_DATE.findAll(note).any { targetDate(it) == eventDate } }
    return if (movedHere) EventStatus.SCHEDULED.name else status
}

/**
 * Decides whether a `POSTPONED` row also changed house: Hole 44 prints "vom 06.10.26 im Hole44
 * auf den 18.03.27 im Säälchen verschoben". The house after the new date is the destination. Another
 * house makes this row `RELOCATED` with that name, as #1867 did for Bi Nuu's `rp`; this row's own
 * house, or no house, leaves it `POSTPONED`. Any other status passes through (#2708).
 *
 * A note with no house after a date falls back to the sentence that carries a relocation verb.
 * UFO's "vom UFO in die Columbiahalle verlegt" beside a rescheduled badge names another house, and
 * MSH's "aus der Max-Schmeling-Halle verlegt" names this one as the origin: both are `RELOCATED`. The
 * sentence, not the whole text, because a biography's "in den letzten 12 Monaten" is no house. The
 * word alone does not count, because "verlegt auf <date>" moves only the date (#3576, #4332).
 *
 * Applied after [resolvePostponement], so the row on the new date is already `SCHEDULED` and never
 * read as a move.
 */
fun resolvePostponedMove(
    status: String,
    notes: List<String>,
    venueSlug: String
): Pair<String, String?> {
    if (status != EventStatus.POSTPONED.name) return status to null
    val here = compactName(venueSlug)
    val datedHouse = notes.firstNotNullOfOrNull { POSTPONED_TO_HOUSE.find(it) }?.let { venueName(it.groupValues[1]) }
    // The first sentence with the verb that names a house; null when a house follows the date.
    val moved =
        notes
            .asSequence()
            .takeIf { datedHouse == null }
            ?.flatMap { SENTENCE_BREAK.split(it) }
            ?.filter { MOVE_VERB.containsMatchIn(it) }
            ?.mapNotNull(::parseRelocation)
            ?.firstOrNull { it.to != null || it.from != null }
    val to = (datedHouse ?: moved?.to)?.takeUnless { namesSameHouse(compactName(it), here) }
    val leftHere = moved != null && moved.to == null && moved.from?.let { namesSameHouse(compactName(it), here) } == true
    return when {
        to != null -> EventStatus.RELOCATED.name to to
        leftHere -> EventStatus.RELOCATED.name to null
        else -> status to null
    }
}

/**
 * The relocation word as a verb. The noun is left out: "Informationen zur Verlegung gibt es unter …"
 * names no house, and would read "Verlegung gibt es unter" as one.
 */
private val MOVE_VERB = Regex("""(?<!vor)verlegt\b|\breloc|\bmoved\b""", RegexOption.IGNORE_CASE)

/** The end of a sentence: punctuation before a space, or a line break. "29.10.26" stays whole. */
private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+|\n""")

/**
 * Decides whether a `RELOCATED` row that names no destination moved its date instead: Kesselhaus
 * flags "verlegt auf 17.08.27" as a move. A new date in a note makes the row `POSTPONED`, so the
 * page can link the new date's row. Any other status, or a row with a destination, passes through (#3613).
 */
fun resolveDatedRelocation(
    status: String,
    relocatedTo: String?,
    notes: List<String>
): String =
    if (status == EventStatus.RELOCATED.name && relocatedTo == null && notes.any { note -> POSTPONED_TO_DATE.findAll(note).any { targetDate(it) != null } }) {
        EventStatus.POSTPONED.name
    } else {
        status
    }

/**
 * The new date of a moved show, as the note states it: Lido's "VERSCHOBEN UND VERLEGT AUF DEN
 * 13.04.27 INS HOLE 44" on a `RELOCATED` row, ufaFabrik's "Verlegt auf den 02.04.2027." on a
 * `POSTPONED` one. No row is matched (ADR-030, #3613, #4334).
 *
 * A note that names [eventDate] before the new date pairs the two, and wins: Die Wühlmäuse lists
 * "Der 06.11.2026, 20 Uhr verschiebt sich auf den 05.11.2027" for each night of a run. Otherwise the
 * first new date counts, after [eventDate] on a `POSTPONED` row. Null on any other status, and for a
 * note that names no such date.
 */
fun relocatedOn(
    status: String,
    notes: List<String>,
    eventDate: LocalDate
): LocalDate? {
    val moved =
        when (status) {
            EventStatus.RELOCATED.name -> { date: LocalDate -> date != eventDate }
            EventStatus.POSTPONED.name -> { date: LocalDate -> date.isAfter(eventDate) }
            else -> return null
        }
    // The pair: the nearest date before an "auf" since the last one is this row's. Nearest, because "vom 05.-08.11.2026
    // verschoben. Der 05.11.2026 verschiebt sich auf …" names the 8th first.
    val paired =
        notes.firstNotNullOfOrNull { note ->
            val matches = POSTPONED_TO_DATE.findAll(note).toList()
            matches.withIndex().firstNotNullOfOrNull { (i, match) ->
                val stretch = note.substring(if (i == 0) 0 else matches[i - 1].range.last + 1, match.range.first)
                targetDate(match).takeIf { it != eventDate && ANY_DATE.findAll(stretch).lastOrNull()?.let(::targetDate) == eventDate }
            }
        }
    return paired ?: notes.firstNotNullOfOrNull { note -> POSTPONED_TO_DATE.findAll(note).mapNotNull(::targetDate).firstOrNull(moved) }
}

/** Any date written as digits: "06.11.2026", "29.10.26". */
private val ANY_DATE = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})(?!\d)""")

/** The new date of a move: "auf den 01.10.2026", "auf 04.11.2026", "auf den 08.10.26". */
private val POSTPONED_TO_DATE = Regex("""\bauf\s+(?:den\s+)?(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})(?!\d)""", RegexOption.IGNORE_CASE)

/**
 * The house named right after the new date: "auf den 18.03.27 im Säälchen", "auf 04.11.2026 ins
 * Metropol". Anchored on the date, because "im" alone opens "im Vorverkauf" and "im Rahmen".
 */
private val POSTPONED_TO_HOUSE =
    Regex(
        """\bauf\s+(?:den\s+)?\d{1,2}\.\d{1,2}\.(?:\d{4}|\d{2})\s+(?:im|ins|in\s+(?:der|dem|den|das))\s+$NAME_BODY""",
        RegexOption.IGNORE_CASE
    )

/** A two-digit year in a note is this century's. */
private const val CENTURY = 2000

/** The date a match names in its day, month and year groups, or null for an impossible one. */
internal fun targetDate(match: MatchResult): LocalDate? {
    val (day, month, year) = match.destructured
    val fullYear = year.toInt().let { if (year.length == 2) CENTURY + it else it }
    return runCatching { LocalDate.of(fullYear, month.toInt(), day.toInt()) }.getOrNull()
}

/**
 * Any spelling of the relocation itself, German or English. "verlegt" may be glued to the name
 * before it — Festsaal's "ins Bi Nuuverlegt" (#1683) — but not to "vor", which moves the date.
 */
private val RELOCATION_WORD = Regex("""(?<!vor)verlegt\b|\bverlegung\b|\breloc|\bmoved\b""", RegexOption.IGNORE_CASE)

/**
 * A venue name in a note: starts with a letter and runs to punctuation, a dash, a bracket, a
 * quote, an asterisk, a line break, the relocation word or the next preposition. The relocation
 * word needs no boundary in front, so the lazy body stops short of a glued "verlegt". A hyphen
 * between two letters is part of the name: "Max-Schmeling-Halle", not "Max" (#4332).
 */
private const val NAME_BODY =
    """(\p{L}[^,.!?;:()\[\]–—/*|<>"„“\n]*?)(?=\s*(?:-(?!\p{L})|\s-|[–—,.!?;:()\[\]/*|<>"„“\n]|(?:hoch)?verlegt\b|verschoben\b|\bins\b|\bin\b|\bnach\b|\bvom\b|\bvon\b|\baus\b|\bmoved\b|$))"""

/** The destination, named with a contracted or articled preposition: "ins Metropol", "in den Privatclub", "nach Kreuzberg", "to Hole 44". */
private val RELOCATION_TO =
    Regex("""\b(?:ins|in\s+(?:das|den|die)|nach|zum|zur|to\s+the|to)\s+$NAME_BODY""", RegexOption.IGNORE_CASE)

/**
 * The destination after a bare "in" — Lido's "vom Lido in Cassiopeia verlegt", Gretchen's
 * "verlegt in Frannz". Read only when [RELOCATION_TO] finds nothing, because prose puts "in
 * Berlin" in front of the real note.
 */
private val RELOCATION_TO_BARE = Regex("""\bin\s+$NAME_BODY""", RegexOption.IGNORE_CASE)

/**
 * The origin: "vom Huxleys", "von der Uber Eats Music Hall", "aus dem Gretchen", "from the
 * Lido". The bare "von" and "aus" are not read: one opens every "Konzert von <act>", the other
 * closes "fällt aus".
 */
private val RELOCATION_FROM =
    Regex("""\b(?:vom|von\s+(?:der|dem)|aus\s+(?:dem|der)|from\s+the|from)\s+$NAME_BODY""", RegexOption.IGNORE_CASE)

/** A quoted span — a tour or album name — in German or English quotes. */
private val QUOTED = Regex("""[„“"][^„“”"\n]*[“”"]""")

/**
 * A move written without a relocation word: "…, das am 30. September in der Uber Eats Music Hall
 * stattfinden sollte, findet nun am 17. Februar 2027 im Huxleys Neue Welt statt." The `nun` /
 * `jetzt` / `stattdessen` is required, because prose says "findet im … statt" everywhere. It
 * only counts on a row whose badge already says `RELOCATED` ([resolveRelocation]).
 */
private val MOVED_HERE_INSTEAD =
    Regex(
        """\bfindet\s+(?:nun|jetzt|stattdessen)\b[^!?\n]{0,60}?\s(?:im|ins|in\s+(?:der|dem|den|das))\s+(\p{L}[^,.!?;:()\n]*?)\s+statt\b""",
        RegexOption.IGNORE_CASE
    )

/** Articles a note may leave in front of the name — "in das Huxleys" writes the article the pattern did not eat. */
private val LEADING_ARTICLE = Regex("""^(?:das|den|die|der|dem|the)\s+""", RegexOption.IGNORE_CASE)

private fun venueName(raw: String): String? =
    raw
        .trim()
        .replace(LEADING_ARTICLE, "")
        .trim()
        .ifBlank { null }
        ?.let(::deshoutHouse)

/**
 * Title-cases a name set wholly in capitals — Lido's "HOCHVERLEGT IN DAS COLUMBIA THEATER" names
 * `Columbia Theater` (#2213). A name with any lowercase letter is the venue's own styling and stays,
 * so "RAW Gelände" keeps its capitals; so does a short initialism and a token with a digit ("SO36").
 */
private fun deshoutHouse(name: String): String =
    if (name.any { it.isLowerCase() } || name.isShortInitialism()) name else name.split(' ').joinToString(" ") { it.deshoutWord() }

/** A name reduced to what two spellings of one house share: lower case, letters and digits only. */
private fun compactName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

/**
 * Whether a note's name and the row's venue slug are one house. A prefix either way, at four
 * characters or more: "Huxleys" against `huxleys-neue-welt`, "Hole" and "Hole44" against
 * `hole-44`, "Frannz" against `frannz-club`.
 */
private fun namesSameHouse(
    name: String,
    here: String
): Boolean = name.length >= MIN_HOUSE_NAME && (here.startsWith(name) || name.startsWith(here))

private const val MIN_HOUSE_NAME = 4

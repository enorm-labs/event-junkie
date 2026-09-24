package de.norm.events.scraper

import de.norm.events.event.EventStatus

// Which end of a move a `RELOCATED` row is, read from the venue's own note (#1551, ADR-030).
// The badge-to-status mapping stays in EventFieldMapping.kt.

/**
 * The two houses a relocation note names, as written: [from] after "vom"/"aus dem"/"from",
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
    if (!RELOCATION_WORD.containsMatchIn(text)) return null
    val to = RELOCATION_TO.find(text) ?: RELOCATION_TO_BARE.find(text)
    return Relocation(
        from = RELOCATION_FROM.find(text)?.let { venueName(it.groupValues[1]) },
        to = to?.let { venueName(it.groupValues[1]) }
    )
}

/**
 * Decides what a `RELOCATED` badge means for the row at [venueSlug]: moved away, or arrived.
 *
 * Returns the status to store and the destination as the note wrote it. A destination that is
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
 * Any spelling of the relocation itself, German or English. "verlegt" may be glued to the name
 * before it — Festsaal's "ins Bi Nuuverlegt" (#1683) — but not to "vor", which moves the date.
 */
private val RELOCATION_WORD = Regex("""(?<!vor)verlegt\b|\bverlegung\b|\breloc|\bmoved\b""", RegexOption.IGNORE_CASE)

/**
 * A venue name in a note: starts with a letter and runs to punctuation, a dash, a bracket, a
 * quote, an asterisk, a line break, the relocation word or the next preposition. The relocation
 * word needs no boundary in front, so the lazy body stops short of a glued "verlegt".
 */
private const val NAME_BODY =
    """(\p{L}[^,.!?;:()\[\]–—/*|<>"„“\n]*?)(?=\s*(?:[-–—,.!?;:()\[\]/*|<>"„“\n]|(?:hoch)?verlegt\b|\bins\b|\bin\b|\bnach\b|\bvom\b|\bvon\b|\baus\b|\bmoved\b|$))"""

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

/** Articles a note may leave in front of the name — "in das Huxleys" writes the article the pattern did not eat. */
private val LEADING_ARTICLE = Regex("""^(?:das|den|die|der|dem|the)\s+""", RegexOption.IGNORE_CASE)

private fun venueName(raw: String): String? =
    raw
        .trim()
        .replace(LEADING_ARTICLE, "")
        .trim()
        .ifBlank { null }

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

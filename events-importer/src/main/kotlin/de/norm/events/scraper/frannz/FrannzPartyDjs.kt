package de.norm.events.scraper.frannz

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName

/**
 * The DJs a Frannz party names in its subtitle (#5299). The shared line-up reading bills nobody on
 * a party, because a party title is not an act. Frannz names the DJs of its own nights in the
 * subtitle, in four shapes. The first shape that yields a name wins:
 *
 * - `An den Decks: Sony Straight (Main // Pop-Explosion) & Marc Hype -Dusty Donuts-(…)`.
 * - Floor labels: `Mainfloor: "Class of '91" Special mit DJ Mig & DJ Stan`. The names follow `mit`.
 *   Without `mit`, they stand before the bracketed style note (`Radaufloor: RadioRadau & DJ Spy
 *   (Dark Side of the 80s)`). A floor with neither (`Salon: Pop Non-Stop`) names a theme, not a DJ.
 *   The floor goes on the act as its stage.
 * - `… after dance feat JULIAN TSF (arg) & PSYCHO JONES (de)`.
 * - Bare `DJ <Name>` tokens: `DJ Tyx Schöne Party-Mix (Mainfloor)`. The style follows the name
 *   with no separator, so the token takes one word, plus a `jr.` or `sr.`.
 *
 * Names split on `&` and `+`, and on a comma before a capital. A comma before a lower-case word is
 * part of the name: `DJ Hey, my Commander`. A subtitle with no cue (`Das Revival der Nullerjahre
 * auf 3 Floors!`) bills nobody.
 */
internal fun frannzPartyDjs(subtitleLines: List<String>): List<ScrapedArtist> {
    val lines = subtitleLines.map { it.replace(LEADING_SYMBOLS, "").trim() }.filter { it.isNotBlank() }
    return decksDjs(lines)
        .ifEmpty { floorDjs(lines) }
        .ifEmpty { featuredDjs(lines) }
        .ifEmpty { djTokens(lines) }
        .distinctBy { it.name.lowercase() }
}

private fun decksDjs(lines: List<String>): List<ScrapedArtist> = lines.flatMap { line -> DECKS.find(line)?.let { djsIn(it.groupValues[1]) }.orEmpty() }

private fun floorDjs(lines: List<String>): List<ScrapedArtist> =
    lines.flatMap { line ->
        val labels = FLOOR_LABEL.findAll(line).toList()
        labels.flatMap { label ->
            val end = labels.firstOrNull { it.range.first > label.range.last }?.range?.first ?: line.length
            val stage = label.groupValues[1].takeUnless { it.equals(BARE_FLOOR, ignoreCase = true) }
            floorBill(line.substring(label.range.last + 1, end))?.let { djsIn(it, stage) }.orEmpty()
        }
    }

/** The part of one floor's text that names its DJs, or `null` when the floor names a theme only. */
private fun floorBill(floor: String): String? {
    MIT.find(floor)?.let { return floor.substring(it.range.last + 1) }
    return floor.substringBefore('(', missingDelimiterValue = "").takeIf { it.isNotBlank() }
}

private fun featuredDjs(lines: List<String>): List<ScrapedArtist> = lines.flatMap { line -> FEATURING.find(line)?.let { djsIn(it.groupValues[1]) }.orEmpty() }

private fun djTokens(lines: List<String>): List<ScrapedArtist> =
    lines.flatMap { line ->
        DJ_TOKEN
            .findAll(line)
            .map { "DJ ${it.groupValues[1]}" }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
            .toList()
    }

private fun djsIn(
    bill: String,
    stage: String? = null
): List<ScrapedArtist> =
    bill
        .replace(BRACKET_NOTE, " ")
        .replace(DASH_NOTE, " ")
        .replace(")", " ")
        .split(NAME_SEPARATOR)
        .map { it.trim().replaceFirst(PLURAL_DJ_LABEL, "").trim() }
        .filter { it.isNotBlank() }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = ArtistRole.DJ, stage = stage) }

/** A headphone emoji or other symbol the venue sets before the line. */
private val LEADING_SYMBOLS = Regex("""^[^\p{L}\p{N}"„]+""")

private val DECKS = Regex("""\bAn\s+den\s+Decks\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

/**
 * `Mainfloor:`, `Radaufloor:`, `1st floor:`, `Salon:`, `Lounge:`. The colon is required: `auf 3 Floors!` is no label.
 */
private val FLOOR_LABEL = Regex("""\b((?:\d+(?:st|nd|rd|th)\s+)?\p{L}*floor|Salon|Lounge)\s*:""", RegexOption.IGNORE_CASE)

/** `auf 1 Floor: mit DJ …` names no floor, so the act gets no stage. */
private const val BARE_FLOOR = "floor"

private val MIT = Regex("""\bmit\s+""", RegexOption.IGNORE_CASE)

private val FEATURING = Regex("""\b(?:feat\.?|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)

/** `DJ <word>`, with a `jr.` or `sr.` kept. Case-sensitive, so the plural `DJs` is no token. */
private val DJ_TOKEN = Regex("""\bDJ\s+(\S+(?:\s+(?:jr|sr|Jr|Sr)\.)?)""")

/** A style or origin note: `(Main // Pop-Explosion)`, `(arg)`. An unclosed one runs to the end. */
private val BRACKET_NOTE = Regex("""\([^)]*\)?""")

/** A note between dashes after a name: `Marc Hype -Dusty Donuts-`. The leading space keeps `Jay-Z`. */
private val DASH_NOTE = Regex("""\s+-\S[^-]*?-(?=\s|$)""")

private val NAME_SEPARATOR = Regex("""\s*(?:&|\+|,(?=\s*\p{Lu}))\s*""")

/** `DJs BImmel & Bammel`: the plural is a label over the names, not part of the first one. */
private val PLURAL_DJ_LABEL = Regex("""^DJs\s+""")

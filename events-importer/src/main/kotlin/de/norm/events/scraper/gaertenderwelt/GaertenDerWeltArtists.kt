package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.splitSupportActs

/**
 * The acts a Gärten der Welt event bills. The park names a concert's acts in prose only, so a
 * [teaser][subtitle] that lists them (`mit Haddaway, Loona, East 17 & Mütze Katze`) bills those
 * as headliners. Without one, a [title] that names a production ([PRODUCTION_TITLE_PATTERN]) bills
 * no title act, and any other title goes through the shared fallback, where it is the act
 * (`Agnes Obel`, `KARAT live in der Arena`). Support acts from the subtitle are kept either way (#3315).
 */
internal fun gaertenDerWeltArtists(
    title: String,
    subtitle: String?,
    eventType: String?
): List<ScrapedArtist> {
    val billed = buildArtistsForEventType(title, subtitle = subtitle, eventType = eventType)
    if (eventType != EventType.CONCERT.name) return billed
    val support = billed.filter { it.role == SUPPORT_ROLE }
    val lineup = teaserLineup(subtitle)
    return when {
        lineup.isNotEmpty() -> lineup + support
        PRODUCTION_TITLE_PATTERN.containsMatchIn(title.trim()) -> support
        else -> billed
    }
}

/**
 * The acts a teaser lists after `mit`, split by the shared [splitSupportActs]. The list
 * needs two names or more and must end the sentence, so `mit Dirk Michaelis im Vorprogramm!` (a
 * support act, already in the title) bills nobody here.
 */
private fun teaserLineup(subtitle: String?): List<ScrapedArtist> =
    subtitle
        ?.let { TEASER_LINEUP_PATTERN.find(it) }
        ?.groupValues
        ?.get(1)
        ?.let(::splitSupportActs)
        ?.filterNot { isNonArtistName(it) }
        ?.map { ScrapedArtist(name = it, role = HEADLINER_ROLE) }
        .orEmpty()

private const val HEADLINER_ROLE = "HEADLINER"
private const val SUPPORT_ROLE = "SUPPORT"

/** One act name in a teaser: capitalised or numeric words (`East 17`, `Mütze Katze`), with an optional `Dr.` before them. */
private const val TEASER_ACT = """(?:Dr\.\s+)?[\p{Lu}\p{N}][\p{L}\p{N}'’-]*(?:\s+[\p{Lu}\p{N}][\p{L}\p{N}'’-]*)*"""

/**
 * A `mit` list of two acts or more, joined by commas, `&` or `und`, that ends its sentence. Only the
 * `M` is folded: `IGNORE_CASE` would let `\p{Lu}` match a lowercase word.
 */
private val TEASER_LINEUP_PATTERN =
    Regex("""\b[Mm]it\s+($TEASER_ACT(?:\s*(?:,|&|\bund\b)\s*$TEASER_ACT)+)\s*(?:[.!?]|$)""")

/**
 * A concert title that names a production, not an act: a drone show (`Drone Art Show: Harry
 * Potter`), a one-word `…konzert` (`Taschenlampenweihnachtskonzert`) or a decade night (`Das Mega
 * 90er Live Open Air`). A score concert (`EPIC SYMPHONY LIVE`) the shared fallback already leaves unbilled.
 */
private val PRODUCTION_TITLE_PATTERN =
    Regex("""\bdrohnen\p{L}*|\bdrone\b|^\p{L}+konzert$|\b\d0er\b""", RegexOption.IGNORE_CASE)

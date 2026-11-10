package de.norm.events.scraper.studioiiii

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.stripArtistSuffix

/** One part of a Studio IIII line-up as an act, or `null` for a part that names nobody. */
internal fun lineupAct(
    part: String,
    role: ArtistRole,
    labelRoom: String?
): ScrapedArtist? {
    val room = ROOM.find(part)
    val live = LIVE_TAIL.containsMatchIn(part)
    val bare = part.removeRange(room?.range ?: IntRange.EMPTY).replace(PERFORMANCE_TAIL, "").replace(LIVE_TAIL, "")
    val name = stripArtistSuffix(bare).blankToNull()?.takeUnless(::isNonArtistName) ?: return null
    val stage = room?.let { roomName(it.groupValues[1]) } ?: labelRoom
    return ScrapedArtist(name = name, role = if (live) ArtistRole.LIVE else role, stage = stage)
}

/**
 * The line-up cut at each `CLUBROOM:` label, paired with the room it names: `… & TOK TOK VS. SOFFY O. CLUBROOM: PENCASONICS`
 * bills Pencasonics in the Clubroom (event 13293). The text before the first label has no room.
 */
internal fun roomSections(text: String): List<Pair<String?, String>> {
    val labels = ROOM_LABEL.findAll(text).toList()
    val rooms = listOf<String?>(null) + labels.map { roomName(it.groupValues[1]) }
    val starts = listOf(0) + labels.map { it.range.last + 1 }
    val ends = labels.map { it.range.first } + text.length
    return rooms.indices.map { rooms[it] to text.substring(starts[it], ends[it]).trimEnd { char -> char.isWhitespace() || char in "/," } }
}

/**
 * The names after the last `DJS` or `WITH` that ends a sentence of prose: `FAMILY, FRIENDS, … CELEBRATE OUR BIRTHDAY
 * WITH US AND DJS CHICO BLANCO, …` bills from Chico Blanco on (event 13293). A marker counts only after
 * [MIN_PROSE_WORDS] words and a word, so `DJS FROM MARS` and `ANA, DJS FROM MARS` keep their act.
 */
internal fun afterProse(text: String): String {
    val marker =
        PROSE_MARKER.findAll(text).lastOrNull { marker ->
            text.substring(0, marker.range.first).split(WHITESPACE).count { it.isNotBlank() } >= MIN_PROSE_WORDS
        } ?: return text
    return text.substring(marker.range.last + 1)
}

private fun roomName(room: String): String = room.lowercase().replaceFirstChar { it.titlecase() }

private val ROOM = Regex("""\s*\((\p{L}*room)\)""", RegexOption.IGNORE_CASE)
private val PERFORMANCE_TAIL = Regex("""\s*\(performance\)\s*$""", RegexOption.IGNORE_CASE)
private val LIVE_TAIL = Regex("""\s*\(live\)\s*$""", RegexOption.IGNORE_CASE)

/** A room label inside the line-up, `CLUBROOM:`; group 1 is the room. */
private val ROOM_LABEL = Regex("""\s*\b(\p{L}*room)\s*:\s*""", RegexOption.IGNORE_CASE)

/** `DJS` or `WITH` after a word, where prose hands over to the names. */
private val PROSE_MARKER = Regex("""(?<=\p{L}\s)(?:DJS|WITH)\s+""", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("""\s+""")
private const val MIN_PROSE_WORDS = 3

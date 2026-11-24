package de.norm.events.scraper.studioiiii

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.WHITESPACE
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

/**
 * The lines that introduce the night in a sentence, `FOR THIS EDITION, WE INVITE PROF. DR. …`, and the heading line right before one,
 * `BLAME YOUR PARENTS` (event 15299). A sentence holds a [PROSE_WORD] and a run of [MIN_SENTENCE_WORDS] words with no act separator.
 */
internal fun introductionLines(lines: List<String>): Set<String> {
    val sentences = lines.indices.filter { isSentence(lines[it]) }
    val headings = sentences.map { it - 1 }.filter { it >= 0 && PROSE_WORD.containsMatchIn(lines[it]) && !BILL_SEPARATOR.containsMatchIn(lines[it]) }
    return (sentences + headings).mapTo(mutableSetOf()) { lines[it] }
}

private fun isSentence(line: String): Boolean =
    afterProse(line) == line &&
        PROSE_WORD.containsMatchIn(line) &&
        line.split(BILL_SEPARATOR).any { part -> part.split(WHITESPACE).count { it.isNotBlank() } >= MIN_SENTENCE_WORDS }

/**
 * A line that opens with `AND` or `&` goes on with the line before it: `WITH DJ SETS BY CLEO` / `AND LUCIA LU` (event 14674).
 * So does any line after a set credit that ends in a comma: `… AND DJ-SETS BY SAZE,` / `XD ERICA, …` (event 15301).
 */
internal fun joinContinuations(lines: List<String>): List<String> =
    lines.fold(mutableListOf()) { joined, line ->
        if (joined.isNotEmpty() && (CONTINUATION.containsMatchIn(line) || OPEN_SET_CREDIT.containsMatchIn(joined.last()))) {
            joined[joined.lastIndex] = "${joined.last().trimEnd()} ${line.trim()}"
        } else {
            joined += line
        }
        joined
    }

private fun roomName(room: String): String = room.lowercase().replaceFirstChar { it.titlecase() }

private val ROOM = Regex("""\s*\((\p{L}*room)\)""", RegexOption.IGNORE_CASE)
private val PERFORMANCE_TAIL = Regex("""\s*\(performance\)\s*$""", RegexOption.IGNORE_CASE)
private val LIVE_TAIL = Regex("""\s*\(live\)\s*$""", RegexOption.IGNORE_CASE)

/** A room label inside the line-up, `CLUBROOM:`; group 1 is the room. */
private val ROOM_LABEL = Regex("""\s*\b(\p{L}*room)\s*:\s*""", RegexOption.IGNORE_CASE)

/** `DJS` or `WITH` after a word, where prose hands over to the names. */
private val PROSE_MARKER = Regex("""(?<=\p{L}\s)(?:DJS|WITH)\s+""", RegexOption.IGNORE_CASE)
private const val MIN_PROSE_WORDS = 3

/** A set credit whose list goes on: a comma ends the line. */
private val OPEN_SET_CREDIT = Regex("""\b(?:SETS?|PERFORMANCES?|MUSIC)\s+(?:BY|WITH)\b.*,\s*$""", RegexOption.IGNORE_CASE)

private const val MIN_SENTENCE_WORDS = 9

/** A pronoun or a form of _be_, which a line-up does not hold beside a long run of words. */
private val PROSE_WORD = Regex("""\b(?:we|us|our|you|your|they|them|their|it|is|are)\b""", RegexOption.IGNORE_CASE)

/** What separates two acts of a line-up line. */
private val BILL_SEPARATOR = Regex("""[,/&:]|\b(?:and|b2b|vs|feat|ft)\b""", RegexOption.IGNORE_CASE)

private val CONTINUATION = Regex("""^\s*(?:and|&)\s""", RegexOption.IGNORE_CASE)

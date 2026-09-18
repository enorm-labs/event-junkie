package de.norm.events.musicbrainz

import de.norm.events.artist.MusicBrainzMatch
import java.text.Normalizer

/**
 * The verdict the match rule reaches for one stored name: the state, and the MBID when it is EXACT.
 */
data class MusicBrainzVerdict(
    val match: MusicBrainzMatch,
    val musicbrainzId: String? = null
) {
    companion object {
        val NONE = MusicBrainzVerdict(MusicBrainzMatch.NONE)
        val AMBIGUOUS = MusicBrainzVerdict(MusicBrainzMatch.AMBIGUOUS)
    }
}

/**
 * The match rule of ADR-031, exactly as `scripts/musicbrainz-match.py` measured it — pure, so
 * every case the spike found is a unit test with the candidates it saw.
 *
 * 1. A candidate counts only when its folded primary [MusicBrainzCandidate.name] equals the folded
 *    query. Alias or sort-name equality alone is AMBIGUOUS. The search score is never read.
 * 2. One counting candidate is EXACT. Several narrow to `country = DE`; exactly one left is EXACT.
 *    Anything else is AMBIGUOUS.
 * 3. No candidate at all is NONE — which is what a title stored as an act looks like.
 */
object MusicBrainzMatcher {
    private const val GERMANY = "DE"
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val CONJUNCTIONS = Regex("\\s+(?:and|und|&)\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val HEAD_SEPARATOR = Regex("\\s+[-–—]\\s+|:\\s+")
    private val DIGITS_ONLY = Regex("\\d+")

    /**
     * The comparison form of a name: NFKD without accents, case-folded, `&`/`and`/`und` as one word,
     * whitespace collapsed, and trailing punctuation dropped. `Motörhead` and `Motorhead` fold alike;
     * `Simon & Garfunkel` and `Simon and Garfunkel` do too.
     */
    fun fold(name: String): String =
        Normalizer
            .normalize(name, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(CONJUNCTIONS, " & ")
            .replace(WHITESPACE, " ")
            .trim(' ', '.', ',', '!')

    fun decide(
        name: String,
        candidates: List<MusicBrainzCandidate>
    ): MusicBrainzVerdict {
        val wanted = fold(name)
        val byName = candidates.filter { fold(it.name) == wanted }
        if (byName.isEmpty()) {
            val byOtherName = candidates.any { candidate -> candidate.otherNames().any { fold(it) == wanted } }
            return if (byOtherName) MusicBrainzVerdict.AMBIGUOUS else MusicBrainzVerdict.NONE
        }
        val chosen = byName.singleOrNull() ?: byName.singleOrNull { it.country == GERMANY }
        return chosen?.let { MusicBrainzVerdict(MusicBrainzMatch.EXACT, it.id) } ?: MusicBrainzVerdict.AMBIGUOUS
    }

    /**
     * The part before the first ` - ` or `: `, when a name looks like `Act - Tour name` or
     * `Festival: Line-up`. Null when there is no such split, or when the head is digits only —
     * `2 - Set` names no act. Reported by the sweep, never stored (#1145 owns the queue).
     */
    fun headOf(name: String): String? {
        val parts = name.split(HEAD_SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val head = parts[0].trim()
        return head.takeIf { it.isNotEmpty() && parts[1].isNotBlank() && !DIGITS_ONLY.matches(it) }
    }

    private fun MusicBrainzCandidate.otherNames(): Sequence<String> =
        sequence {
            sortName?.let { yield(it) }
            aliases.forEach { yield(it.name) }
        }
}

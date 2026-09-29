package de.norm.events.discogs

import de.norm.events.artist.DiscogsMatch
import de.norm.events.musicbrainz.MusicBrainzMatcher

/** The verdict the match rule reaches for one stored name: the state, and the id and page when it is EXACT. */
data class DiscogsVerdict(
    val match: DiscogsMatch,
    val discogsId: Long? = null,
    val discogsUrl: String? = null
) {
    companion object {
        val NONE = DiscogsVerdict(DiscogsMatch.NONE)
        val AMBIGUOUS = DiscogsVerdict(DiscogsMatch.AMBIGUOUS)
    }
}

/**
 * The match rule of `scripts/discogs-match.py`, which the spike on #1549 measured: pure, so every
 * case is a unit test with the candidates Discogs returned.
 *
 * 1. A candidate counts only when its folded title, homonym suffix stripped, equals the folded
 *    query. The search's own order decides nothing: it is fuzzy, and `Boris Brejcha` also returns
 *    `Luke Mandala`.
 * 2. One counting candidate without a suffix is EXACT. Several are AMBIGUOUS: the search returns
 *    no country to break the tie on, and stripping the suffix makes every homonym count.
 * 3. A counting candidate with a suffix is AMBIGUOUS even alone. `Kevin (27)` says Discogs holds
 *    at least 27 artists of that name, and the search returned one of them.
 * 4. No candidate is NONE.
 */
object DiscogsMatcher {
    private const val ARTIST = "artist"
    private const val SITE = "https://www.discogs.com"
    private val HOMONYM_SUFFIX = Regex("\\s*\\(\\d+\\)$")

    fun decide(
        name: String,
        candidates: List<DiscogsCandidate>
    ): DiscogsVerdict {
        val wanted = MusicBrainzMatcher.fold(name)
        val byTitle = candidates.filter { it.type == ARTIST && MusicBrainzMatcher.fold(it.title.replace(HOMONYM_SUFFIX, "")) == wanted }
        val chosen = byTitle.singleOrNull()?.takeUnless { HOMONYM_SUFFIX.containsMatchIn(it.title) }
        return when {
            byTitle.isEmpty() -> DiscogsVerdict.NONE
            chosen == null -> DiscogsVerdict.AMBIGUOUS
            else -> DiscogsVerdict(DiscogsMatch.EXACT, chosen.id, pageOf(chosen))
        }
    }

    /** The public page of [candidate]: its `uri` on discogs.com, or the bare id path when the search gave none. */
    private fun pageOf(candidate: DiscogsCandidate): String = SITE + (candidate.uri?.takeIf { it.startsWith("/artist/") } ?: "/artist/${candidate.id}")
}

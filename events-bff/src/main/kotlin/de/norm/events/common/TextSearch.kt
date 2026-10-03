package de.norm.events.common

import de.norm.events.EVENTS_SCHEMA

/**
 * The one matching rule behind every `q` the public API takes (#2428): events, venues, promoters,
 * artists.
 *
 * A searched column `x` has a stored folded twin `x_search` (V090), and the term folds through
 * `search_norm`, so `aeden` finds `ÆDEN` and both `neukolln` and `neukoelln` find `Neukölln`. Spaces
 * are dropped before the substring test, so `kit kat` finds `KitKatClub`.
 *
 * A search runs [strict][matches] first. Only when that finds nothing does it run again by trigram
 * [similarity][similar], so `berghian` finds `Berghain` while `tango` keeps its two real hits. On
 * staging data a similarity pass beside the strict one added 53 rows to `tango` and 80 to `metal`.
 *
 * A term that folds to nothing — `%`, `_`, `!!!` — would match every row as `LIKE '%%'`. It falls
 * back to the literal `ILIKE` with [escapeLike], which keeps `%` a letter (#1456).
 */
object TextSearch {
    const val TERM = "q"
    const val PATTERN = "qPattern"

    /** Below the `pg_trgm` default of 0.6, which misses `berghian` (0.56) and `saelchen` (0.50). */
    private const val SIMILARITY_THRESHOLD = 0.5

    /** Shorter terms have too few trigrams to score; `lio` scores 0.5 against `lido`. */
    private const val SIMILARITY_MIN_LENGTH = 4

    private const val NORM = "$EVENTS_SCHEMA.search_norm"
    private const val TERM_NORM = "$NORM(:$TERM)"
    private const val TERM_JOINED = "replace($TERM_NORM, ' ', '')"

    private val WHITESPACE = Regex("\\s+")

    /** The visitor's input trimmed with its inner whitespace collapsed, or null when blank. */
    fun term(input: String?): String? = input?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

    /** The bind values for a [term]; the [similar] pass has no literal pattern to bind. */
    fun params(
        term: String,
        bySimilarity: Boolean
    ): Map<String, Any> = if (bySimilarity) mapOf(TERM to term) else mapOf(TERM to term, PATTERN to "%${term.escapeLike()}%")

    /** Whether a strict search for [term] that found nothing is worth a [similar] pass. */
    fun allowsSimilar(term: String?): Boolean = term != null && term.count { it.isLetterOrDigit() } >= SIMILARITY_MIN_LENGTH

    /** Runs [search] strictly, and again by similarity when that [found] nothing and [term] [allowsSimilar]. */
    suspend fun <T> strictThenSimilar(
        term: String?,
        found: (T) -> Boolean,
        search: suspend (bySimilarity: Boolean) -> T
    ): T {
        val strict = search(false)
        return if (!found(strict) && allowsSimilar(term)) search(true) else strict
    }

    /** True when [column] contains the bound term, folded and with spaces dropped. */
    fun matches(column: String): String =
        "(CASE WHEN $TERM_NORM = '' THEN $column ILIKE :$PATTERN " +
            "ELSE replace(${folded(column)}, ' ', '') LIKE '%' || $TERM_JOINED || '%' END)"

    /** True when [column] holds a word close to the bound term. */
    fun similar(column: String): String = "(${similarity(column)} >= $SIMILARITY_THRESHOLD)"

    /** The predicate for one pass: [similar] when [bySimilarity], else [matches]. */
    fun predicate(
        column: String,
        bySimilarity: Boolean
    ): String = if (bySimilarity) similar(column) else matches(column)

    /**
     * A leading sort key for a name list: on the strict pass 0 exact, 1 prefix, 2 anywhere; on the
     * similarity pass the closest first.
     */
    fun rank(
        column: String,
        bySimilarity: Boolean
    ): String =
        if (bySimilarity) {
            "${similarity(column)} DESC"
        } else {
            "CASE WHEN $TERM_NORM = ANY(string_to_array(${folded(column)}, ' | ')) THEN 0 " +
                "WHEN ${folded(column)} LIKE $TERM_NORM || '%' OR ${folded(column)} LIKE '% | ' || $TERM_NORM || '%' THEN 1 ELSE 2 END"
        }

    private fun similarity(column: String): String = "$EVENTS_SCHEMA.word_similarity($TERM_NORM, ${folded(column)})"

    private fun folded(column: String): String = "${column}_search"
}

/**
 * Escapes a visitor's search term for use inside a bound `ILIKE` pattern.
 *
 * `%` and `_` in a search term are letters to the visitor, not wildcards: unescaped, `q=%` matches
 * the whole catalogue at full page cost (#1456). PostgreSQL's default escape character is `\`, so
 * no `ESCAPE` clause is needed; the backslash goes first so an escaped `%` is not escaped twice.
 */
fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

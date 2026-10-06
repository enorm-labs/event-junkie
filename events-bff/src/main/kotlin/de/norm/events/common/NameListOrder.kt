package de.norm.events.common

import org.springframework.data.domain.Pageable

/**
 * The `ORDER BY` behind a name list that also answers a search: artists, promoters and venues.
 *
 * [sortColumns] whitelists the sort properties, so a request cannot name a column. Every order ends
 * in [tiebreaker], or `LIMIT`/`OFFSET` over a tie repeats one row and skips another.
 */
class NameListOrder(
    private val nameColumn: String,
    private val sortColumns: Map<String, String>,
    private val tiebreaker: List<String>
) {
    /**
     * Without a sort, a search puts the closest matches first and the list is by name; a chosen
     * sort, A–Z included, is never reordered by relevance.
     */
    fun clause(
        pageable: Pageable,
        term: String?,
        bySimilarity: Boolean
    ): String {
        val clauses =
            pageable.sort.toList().mapNotNull { order ->
                sortColumns[order.property]?.let { column -> "$column ${if (order.isAscending) "ASC" else "DESC"}" }
            }
        val rank = TextSearch.rank(nameColumn, bySimilarity).takeIf { term != null && clauses.isEmpty() }
        return "ORDER BY ${(listOfNotNull(rank) + clauses + tiebreaker).joinToString(", ")}"
    }
}

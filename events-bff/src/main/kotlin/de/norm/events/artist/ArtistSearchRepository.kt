package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.TextSearch
import de.norm.events.common.countQuery
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** An ordered page of artist ids plus the total count of matches across all pages. */
data class ArtistIdPage(
    val ids: List<Long>,
    val total: Long
)

/**
 * The artist name search ([TextSearch]). Raw SQL because the rule calls `search_norm`, which a
 * derived query cannot. A search sorted by name puts the closest matches first.
 */
@Repository
class ArtistSearchRepository(
    private val databaseClient: DatabaseClient
) {
    suspend fun search(
        term: String,
        pageable: Pageable,
        countCap: Int? = null
    ): ArtistIdPage = TextSearch.strictThenSimilar(term, found = { it.total > 0 }) { bySimilarity -> search(term, bySimilarity, pageable, countCap) }

    private suspend fun search(
        term: String,
        bySimilarity: Boolean,
        pageable: Pageable,
        countCap: Int?
    ): ArtistIdPage {
        val where = "WHERE ${TextSearch.predicate("a.name", term, bySimilarity)}"
        val total =
            databaseClient
                .sql(countQuery("$EVENTS_SCHEMA.artist a", where, countCap))
                .bindTerm(term, bySimilarity)
                .map { row: Readable -> row.get(0, Long::class.javaObjectType) ?: 0L }
                .one()
                .awaitSingle()

        if (total == 0L) return ArtistIdPage(emptyList(), 0L)

        val ids =
            databaseClient
                .sql("SELECT a.id FROM $EVENTS_SCHEMA.artist a $where ${orderBy(pageable, bySimilarity)} LIMIT :limit OFFSET :offset")
                .bindTerm(term, bySimilarity)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .map { row: Readable -> requireNotNull(row.get("id", Long::class.javaObjectType)) { "Artist id projection returned a null id" } }
                .all()
                .collectList()
                .awaitSingle()

        return ArtistIdPage(ids, total)
    }

    private fun DatabaseClient.GenericExecuteSpec.bindTerm(
        term: String,
        bySimilarity: Boolean
    ): DatabaseClient.GenericExecuteSpec = TextSearch.params(term, bySimilarity).entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }

    private fun orderBy(
        pageable: Pageable,
        bySimilarity: Boolean
    ): String {
        val clauses =
            pageable.sort.toList().mapNotNull { order ->
                SORT_COLUMNS[order.property]?.let { column -> "$column ${if (order.isAscending) "ASC" else "DESC"}" }
            }
        val rank = TextSearch.rank("a.name", bySimilarity).takeIf { pageable.sort.firstOrNull()?.property == "name" }
        return "ORDER BY ${(listOfNotNull(rank) + clauses + TIEBREAKER).joinToString(", ")}"
    }

    companion object {
        /** The derived query's columns, so a search keeps the order the unfiltered list has. */
        val SORT_COLUMNS =
            mapOf(
                "name" to "a.name",
                "slug" to "a.slug"
            )

        private const val TIEBREAKER = "a.id ASC"
    }
}

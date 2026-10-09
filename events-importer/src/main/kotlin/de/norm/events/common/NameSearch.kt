package de.norm.events.common

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query

/**
 * One page of the [type] rows whose `name` contains [term], ignoring case, and the count of all of
 * them: the admin's name search (#2988). [narrowedBy] adds a filter of the caller's, such as the
 * promoters' review stamp.
 *
 * `%` and `_` in [term] match only themselves: PostgreSQL's default escape character is `\`, and the
 * backslash is escaped first so an escaped `%` is not escaped twice.
 */
suspend fun <T : Any> R2dbcEntityTemplate.pageByName(
    type: Class<T>,
    term: String,
    pageable: Pageable,
    narrowedBy: Criteria? = null
): Pair<List<T>, Long> {
    val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    val byName = Criteria.where("name").like("%$escaped%").ignoreCase(true)
    return pageMatching(type, narrowedBy?.and(byName) ?: byName, pageable)
}

/** One page of the [type] rows that match [criteria], and the count of all of them. */
suspend fun <T : Any> R2dbcEntityTemplate.pageMatching(
    type: Class<T>,
    criteria: Criteria,
    pageable: Pageable
): Pair<List<T>, Long> {
    val rows =
        select(type)
            .matching(Query.query(criteria).with(pageable))
            .all()
            .asFlow()
            .toList()
    val total = count(Query.query(criteria), type).awaitSingle()
    return rows to total
}

package de.norm.events.festival

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDate

interface FestivalRepository : CoroutineCrudRepository<FestivalEntity, Long> {
    /**
     * The festivals running on [today] or starting by [horizon], at most [limit]. A running festival
     * started on or before [today], so ordering by `first_day` puts every running one first.
     */
    @Query(
        "SELECT * FROM $EVENTS_SCHEMA.festival WHERE last_day >= :today AND first_day <= :horizon " +
            "ORDER BY first_day, id LIMIT :limit"
    )
    fun findCurrent(
        today: LocalDate,
        horizon: LocalDate,
        limit: Int
    ): Flow<FestivalEntity>
}

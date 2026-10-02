package de.norm.events.venue

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface VenueRepository : CoroutineCrudRepository<VenueEntity, Long> {
    /** Finds a single venue by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): VenueEntity?

    /** Batch-fetches venues by ID — used to resolve embedded venue summaries for an event page. */
    fun findByIdIn(ids: Collection<Long>): Flow<VenueEntity>
}

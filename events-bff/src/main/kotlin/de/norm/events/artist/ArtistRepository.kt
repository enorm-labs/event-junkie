package de.norm.events.artist

import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ArtistRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /** Finds all artists with pagination and sorting applied via [pageable]. */
    fun findAllBy(pageable: Pageable): Flow<ArtistEntity>

    /** Finds a single artist by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): ArtistEntity?

    /** Batch-fetches artists by ID — used to resolve event lineups for an event page. */
    fun findByIdIn(ids: Collection<Long>): Flow<ArtistEntity>
}

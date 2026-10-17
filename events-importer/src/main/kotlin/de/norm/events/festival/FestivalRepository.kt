package de.norm.events.festival

import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface FestivalRepository : CoroutineCrudRepository<FestivalEntity, Long> {
    suspend fun findBySlug(slug: String): FestivalEntity?

    fun findAllBy(pageable: Pageable): Flow<FestivalEntity>
}

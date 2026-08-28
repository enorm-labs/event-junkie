package de.norm.events.sourcelicence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/** Read access to the licence columns on `event_source`. */
@Repository
interface SourceLicenceRepository : CoroutineCrudRepository<SourceLicenceEntity, Long> {
    fun findByIdIn(ids: Collection<Long>): Flow<SourceLicenceEntity>
}

package de.norm.events.sourcelicence

import de.norm.events.licence.SourceLicences
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

/**
 * Looks up what each source permits, in one query for a whole page.
 *
 * Deliberately returns the answers rather than applying them. The caller owns the types being
 * redacted, so keeping the edge one-way is what stops `event` and `sourcelicence` forming a cycle.
 */
@Service
class SourceLicenceGate(
    private val repository: SourceLicenceRepository
) {
    /**
     * Resolves [sourceIds] to their licences. Ids that no longer exist are simply absent from the
     * map, and callers read that as [SourceLicences.UNKNOWN_SOURCE].
     */
    suspend fun forSources(sourceIds: Collection<Long>): Map<Long, SourceLicences> {
        val distinct = sourceIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return repository
            .findByIdIn(distinct)
            .toList()
            .mapNotNull { entity ->
                entity.id?.let { id ->
                    id to
                        SourceLicences.of(entity.descriptionLicence, entity.imageLicence)
                }
            }.toMap()
    }
}

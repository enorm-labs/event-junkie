package de.norm.events.sourcelicence

import de.norm.events.licence.SourceLicence
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

/**
 * What one source permits, for the two fields that need permission.
 *
 * `null` means nobody reviewed that field, which is not the same as
 * [SourceLicence.UNCLEAR] and displays for the same reason.
 */
data class SourceLicences(
    val description: SourceLicence?,
    val image: SourceLicence?
) {
    /**
     * Whether the description must be withheld.
     *
     * **Only [SourceLicence.PROHIBITED] withholds.** `UNCLEAR` and `null` both display. That is the
     * decision taken on #283 and it is deliberately fail-open: silence from a venue is not a
     * refusal, and blanking every unreviewed source would remove material nobody objected to.
     *
     * `docs/SCRAPING_POSITION.md` §3.1 records what this accepts. The test that pins every branch
     * of this rule is what makes flipping it a decision rather than a tidy-up.
     */
    fun withholdsDescription(): Boolean = description == SourceLicence.PROHIBITED

    /** The same rule for images, answered from the source's own column. */
    fun withholdsImage(): Boolean = image == SourceLicence.PROHIBITED

    companion object {
        /**
         * What an event with no source at all permits.
         *
         * `event.event_source_id` is nullable and `ON DELETE SET NULL`, so an event outlives the
         * source that produced it. Such a row has no prohibition attached to it and therefore
         * displays, which is the same answer fail-open gives everywhere else.
         */
        val UNKNOWN_SOURCE = SourceLicences(description = null, image = null)
    }
}

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
                        SourceLicences(
                            description = entity.descriptionLicence?.let(SourceLicence::parseOrProhibited),
                            image = entity.imageLicence?.let(SourceLicence::parseOrProhibited)
                        )
                }
            }.toMap()
    }
}

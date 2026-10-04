package de.norm.events.event

import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.normalizeGenre
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The pins a hand edit sets on an event, and their removal (ADR-042). */
@Service
class EventPinService(
    private val eventRepository: EventRepository,
    private val eventArtistRepository: EventArtistRepository,
    private val eventPromoterRepository: EventPromoterRepository,
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val genreTagRepository: GenreTagRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Removes the pin on [field] from event [id], so the next import that reads its page writes the
     * source's value again (ADR-042). Removing a pin the event does not carry changes nothing.
     *
     * @throws UnknownPinnedFieldException if [field] names no [PinnedField].
     * @throws EventNotFoundException if no event with the given [id] exists.
     */
    @Transactional
    suspend fun unpin(
        id: Long,
        field: String
    ) {
        val pin = PinnedField.fromKey(field) ?: throw UnknownPinnedFieldException(field)
        val existing = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        if (pin.key !in existing.pinnedFields) return
        eventRepository.save(existing.copy(pinnedFields = existing.pinnedFields - pin.key))
        logger.info { "Unpinned ${pin.key} on event $id: the next import writes the source's value" }
    }

    /**
     * The fields this edit changes on [existing]: each column whose value moves, and each join table
     * whose rows change. Read before the associations are replaced. A value sent unchanged pins nothing.
     */
    suspend fun editedFields(
        id: Long,
        existing: EventEntity,
        updated: EventEntity,
        request: EventRequest
    ): List<PinnedField> {
        val storedLineup =
            eventArtistRepository
                .findByEventId(id)
                .toList()
                .map { listOf(it.artistId, it.role, it.billingOrder, it.stage) }
                .toSet()
        val editedLineup = request.artists.map { listOf(it.artistId, it.role.name, it.billingOrder, it.stage) }.toSet()
        val storedPromoters =
            eventPromoterRepository
                .findByEventId(id)
                .toList()
                .map { it.promoterId }
                .toSet()
        val storedGenreTagIds = eventGenreTagRepository.findByEventId(id).toList().map { it.genreTagId }
        val storedGenres =
            genreTagRepository
                .findAllById(storedGenreTagIds)
                .toList()
                .map { it.slug }
                .toSet()
        val editedGenres = normalizeGenre(request.genre).map { SlugGenerator.slugify(it) }.toSet()
        return PinnedField.entries.filter { it.differs(updated, existing) } +
            listOfNotNull(
                PinnedField.LINEUP.takeIf { storedLineup != editedLineup },
                PinnedField.PROMOTERS.takeIf { storedPromoters != request.promoterIds.toSet() },
                PinnedField.GENRES.takeIf { storedGenres != editedGenres }
            )
    }
}

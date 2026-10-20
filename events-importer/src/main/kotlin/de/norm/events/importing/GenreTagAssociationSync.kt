package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.genretag.EventGenreTagEntity
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagEntity
import de.norm.events.genretag.GenreTagOrigin
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.genreFamily
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.slug.SlugGenerator
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component

/**
 * The genre-tag step of [AssociationSyncService]: normalizes the source's genre text into tags,
 * resolves them by slug, auto-creating unknown ones, and diff-syncs `event_genre_tag`. Runs within the caller's transaction.
 */
@Component
class GenreTagAssociationSync(
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val genreTagRepository: GenreTagRepository
) {
    /**
     * Normalizes genre strings into canonical tags, batch-fetches known tags by slug and
     * auto-creates the rest.
     *
     * @return genre tag slug to persisted [GenreTagEntity].
     */
    suspend fun resolveAllGenreTags(scrapedEvents: List<ScrapedEvent>): Map<String, GenreTagEntity> {
        val allGenreNames = scrapedEvents.flatMap { normalizeGenre(it.storedGenre()) }.distinct()
        if (allGenreNames.isEmpty()) return emptyMap()

        val allSlugs = allGenreNames.map { SlugGenerator.slugify(it) }.toSet()
        val genreTagCache =
            genreTagRepository
                .findBySlugIn(allSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        allGenreNames
            .distinctBy { SlugGenerator.slugify(it) }
            .forEach { name ->
                resolveOrCreate(
                    name = name,
                    cache = genreTagCache,
                    insertIfAbsent = { slug -> genreTagRepository.insertIfAbsent(name, slug, genreFamily(slug)?.slug) },
                    findBySlug = { slug -> genreTagRepository.findBySlug(slug) }
                )
            }

        return genreTagCache
    }

    /** The ids among [eventIds] that link at least one tag the source named. A derived tag does not count. */
    suspend fun eventIdsWithSourceTags(eventIds: List<Long>): Set<Long> =
        eventGenreTagRepository
            .findByEventIdIn(eventIds)
            .toList()
            .filter { it.origin == GenreTagOrigin.SOURCE.name }
            .mapTo(mutableSetOf()) { it.eventId }

    /** The slugs of the genre tags each of [events] links, by event id. */
    suspend fun storedSlugs(events: List<EventEntity>): Map<Long, Set<String>> =
        storedSlugs(events, { eventGenreTagRepository.findByEventIdIn(it).toList() }, { it.eventId to it.genreTagId }) { ids ->
            genreTagRepository.findAllById(ids).toList().associate { it.id to it.slug }
        }

    /**
     * Synchronizes the source's genre tags by diff. A derived tag (ADR-047) stays while the source names none, for
     * [GenreDerivationService] to replace. Once the source names a genre, a derived row of that tag becomes a source row and the rest go.
     */
    suspend fun syncGenreTagAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        genreTagCache: Map<String, GenreTagEntity>
    ) {
        val existingByEventId =
            fetchExistingAssociationsByEventId(
                savedEvents,
                { eventGenreTagRepository.findByEventIdIn(it).toList() },
                EventGenreTagEntity::eventId
            ) ?: return

        val genresBySourceId = scrapedEvents.associate { it.sourceId to normalizeGenre(it.storedGenre()) }
        val toInsert = mutableListOf<EventGenreTagEntity>()
        val toPromote = mutableListOf<EventGenreTagEntity>()
        val toDeleteIds = mutableListOf<Long>()

        for (saved in savedEvents) {
            val (eventId, existingByGenreTagId, existing) =
                eventAssociationContext(saved, existingByEventId, EventGenreTagEntity::genreTagId)

            val desiredGenreNames = genresBySourceId[saved.sourceId].orEmpty()
            val desiredGenreTagIds = mutableSetOf<Long>()

            for (genreName in desiredGenreNames) {
                val slug = SlugGenerator.slugify(genreName)
                val genreTagId =
                    requireNotNull(genreTagCache[slug]?.id) {
                        "Genre tag '$slug' must be resolved before syncing associations"
                    }
                desiredGenreTagIds.add(genreTagId)

                val current = existingByGenreTagId[genreTagId]
                when {
                    current == null -> toInsert.add(EventGenreTagEntity(eventId = eventId, genreTagId = genreTagId))
                    current.origin != GenreTagOrigin.SOURCE.name -> toPromote.add(current.copy(origin = GenreTagOrigin.SOURCE.name))
                }
            }

            existing
                .filter { it.genreTagId !in desiredGenreTagIds }
                .filter { desiredGenreTagIds.isNotEmpty() || it.origin == GenreTagOrigin.SOURCE.name }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventGenreTagRepository.deleteAllById(toDeleteIds)
        }
        if (toPromote.isNotEmpty()) {
            eventGenreTagRepository.saveAll(toPromote).toList()
        }
        if (toInsert.isNotEmpty()) {
            eventGenreTagRepository.saveAll(toInsert).toList()
        }
    }
}

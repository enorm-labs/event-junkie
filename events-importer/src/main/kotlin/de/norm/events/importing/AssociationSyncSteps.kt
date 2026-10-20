package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging

// The steps the artist, promoter and genre-tag syncs share.

private val logger = KotlinLogging.logger {}

/**
 * Pre-computed per-event context for diff syncing, shared by the artist, promoter and genre-tag syncs;
 * supports destructuring.
 *
 * @param T the association entity type.
 */
internal data class EventAssociationContext<T>(
    val eventId: Long,
    val existingByForeignKey: Map<Long, T>,
    val existing: List<T>
)

/**
 * Batch-fetches existing associations for all saved events, grouped by event ID.
 *
 * @return grouped associations, or `null` if no saved events have IDs.
 */
internal suspend fun <T> fetchExistingAssociationsByEventId(
    savedEvents: List<EventEntity>,
    fetchByEventIds: suspend (List<Long>) -> List<T>,
    getEventId: (T) -> Long
): Map<Long, List<T>>? {
    val savedEventIds = savedEvents.mapNotNull { it.id }
    if (savedEventIds.isEmpty()) return null
    return fetchByEventIds(savedEventIds).groupBy(getEventId)
}

/**
 * The per-event association context: event ID, existing associations, indexed by foreign key.
 */
internal fun <T> eventAssociationContext(
    saved: EventEntity,
    existingByEventId: Map<Long, List<T>>,
    foreignKeyExtractor: (T) -> Long
): EventAssociationContext<T> {
    val eventId = requireNotNull(saved.id) { "Saved event must have an ID" }
    val existing = existingByEventId[eventId].orEmpty()
    return EventAssociationContext(
        eventId = eventId,
        existingByForeignKey = existing.associateBy(foreignKeyExtractor),
        existing = existing
    )
}

/** The slugs of the rows each of [events] links, by event id: one query for the links, one for the names. */
internal suspend fun <T> storedSlugs(
    events: List<EventEntity>,
    fetchLinks: suspend (List<Long>) -> List<T>,
    link: (T) -> Pair<Long, Long>,
    fetchSlugs: suspend (Set<Long>) -> Map<Long?, String>
): Map<Long, Set<String>> {
    if (events.isEmpty()) return emptyMap()
    val links = fetchLinks(events.mapNotNull { it.id }).map(link)
    val slugById = fetchSlugs(links.mapTo(mutableSetOf()) { it.second })
    return links.groupBy({ it.first }, { slugById[it.second] }).mapValues { (_, slugs) -> slugs.filterNotNull().toSet() }
}

/**
 * Generic resolve-or-create for slug-based entities: [cache] first, then a conflict-tolerant
 * `INSERT … ON CONFLICT DO NOTHING` via [insertIfAbsent] and [findBySlug]. Not
 * save-then-catch: these run inside the caller's import transaction, imports run concurrently
 * and race to insert the same shared slug, and in PostgreSQL a failed statement aborts the whole
 * transaction, so re-querying after a unique violation fails with "current transaction is
 * aborted". `ON CONFLICT DO NOTHING` never raises; a lost race is a `0`-row no-op and
 * [findBySlug] returns the winner's row.
 *
 * @param T the entity type.
 * @param name the human-readable name to slugify and resolve.
 * @param cache mutable slug to entity map shared across the batch.
 * @param insertIfAbsent conflict-tolerant insert; returns rows inserted.
 * @param findBySlug fetches the entity by slug, always present after [insertIfAbsent].
 * @param slug the row's slug; an artist passes [de.norm.events.artist.artistSlugFor], which can differ from the name's.
 */
internal suspend fun <T : Any> resolveOrCreate(
    name: String,
    cache: MutableMap<String, T>,
    insertIfAbsent: suspend (slug: String) -> Int,
    findBySlug: suspend (slug: String) -> T?,
    slug: String = SlugGenerator.slugify(name)
): T {
    cache[slug]?.let { return it }

    val created = insertIfAbsent(slug) == 1
    val entity =
        findBySlug(slug)
            ?: error("Entity with slug '$slug' not found after insert-if-absent")

    if (created) {
        logger.info { "Auto-created ${entity::class.simpleName} '$name' (slug=$slug)" }
    } else {
        logger.debug { "Reused existing ${entity::class.simpleName} (slug=$slug)" }
    }
    cache[slug] = entity
    return entity
}

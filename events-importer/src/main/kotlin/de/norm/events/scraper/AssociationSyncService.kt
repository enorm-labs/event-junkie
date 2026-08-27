package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.canonicalArtistName
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.genretag.EventGenreTagEntity
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagEntity
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.normalizeGenre
import de.norm.events.promoter.PromoterEntity
import de.norm.events.promoter.PromoterRepository
import de.norm.events.promoter.canonicalPromoterName
import de.norm.events.promoter.isNonPromoterName
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

/**
 * Resolves artists, promoters, and genre tags by slug (auto-creating unknown ones) and
 * synchronizes many-to-many join-table associations for upserted events.
 *
 * Uses a diff strategy to minimize write churn: only inserts new associations,
 * updates changed ones (artist role/billing order), and deletes stale ones.
 * Entity resolution handles concurrent creation gracefully via unique-constraint
 * violation fallback.
 *
 * Extracted from [EventUpsertService] to separate association management from
 * event-level persistence concerns. Called within the same transactional boundary
 * managed by the upstream caller.
 */
@Service
@Suppress("TooManyFunctions") // Logically cohesive — groups artist, promoter, and genre tag association management
class AssociationSyncService(
    private val eventArtistRepository: EventArtistRepository,
    private val eventPromoterRepository: EventPromoterRepository,
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val artistRepository: ArtistRepository,
    private val promoterRepository: PromoterRepository,
    private val genreTagRepository: GenreTagRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Resolves all referenced artists and promoters, then synchronizes their
     * join-table associations for the given saved events.
     *
     * This is the single entry point called by [EventUpsertService] after events
     * have been upserted. The full pipeline:
     * 1. Batch-fetch known artists by slug, auto-create unknown ones.
     * 2. Diff-sync artist associations (insert/update/delete).
     * 3. Batch-fetch known promoters by slug, auto-create unknown ones.
     * 4. Diff-sync promoter associations (insert/delete).
     * 5. Normalize genre strings, batch-fetch known genre tags, auto-create unknown ones.
     * 6. Diff-sync genre tag associations (insert/delete).
     *
     * @param savedEvents the persisted event entities (must have non-null IDs).
     * @param scrapedEvents the raw scraped events (source of artist/promoter/genre data).
     */
    suspend fun resolveAndSyncAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>
    ) {
        val artistCache = resolveAllArtists(scrapedEvents)
        syncArtistAssociations(savedEvents, scrapedEvents, artistCache)

        val promoterCache = resolveAllPromoters(scrapedEvents)
        syncPromoterAssociations(savedEvents, scrapedEvents, promoterCache)

        val genreTagCache = resolveAllGenreTags(scrapedEvents)
        syncGenreTagAssociations(savedEvents, scrapedEvents, genreTagCache)
    }

    // -- Artist resolution --

    /**
     * Batch-fetches all known artists by slug and auto-creates any unknown artists.
     *
     * @return a cache mapping artist slug → persisted [ArtistEntity], covering all
     *   artists referenced by the scraped events.
     */
    private suspend fun resolveAllArtists(scrapedEvents: List<ScrapedEvent>): Map<String, ArtistEntity> {
        // Canonicalize *before* slugging, as the promoter path does. De-shouting alone never
        // changes the slug (it is casing-only, and slugs are case-insensitive), but a curated
        // NAME_CORRECTIONS entry can — "OXO86" resolves to "Oxo 86", i.e. slug `oxo-86` rather
        // than `oxo86`. Slugging the raw name here would then look up a different row than
        // resolveOrCreateArtist creates, and the two spellings would stay fragmented.
        val allArtistSlugs =
            scrapedEvents
                .flatMap { it.artists }
                .map { SlugGenerator.slugify(canonicalArtistName(it.name)) }
                .toSet()
        val artistCache =
            artistRepository
                .findBySlugIn(allArtistSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        // Auto-create only the artists not already in the database. The stored display name is
        // canonicalized first (see canonicalArtistName): de-shouting so an act isn't frozen
        // SHOUTING by whichever venue imported it first, plus any curated spelling correction.
        scrapedEvents
            .flatMap { it.artists }
            .distinctBy { SlugGenerator.slugify(canonicalArtistName(it.name)) }
            .forEach { resolveOrCreateArtist(canonicalArtistName(it.name), artistCache) }

        return artistCache
    }

    /**
     * Resolves an artist by slug (derived from name) from the [artistCache],
     * or auto-creates a new artist entity if no match is found. Newly created
     * artists are added to the cache so subsequent events in the same batch
     * can reuse them without additional database queries.
     *
     * Handles concurrent artist creation gracefully: if another import creates
     * the same artist between our cache check and save, the unique constraint
     * violation on `artist.slug` is caught and we fall back to a lookup.
     */
    private suspend fun resolveOrCreateArtist(
        name: String,
        artistCache: MutableMap<String, ArtistEntity>
    ): ArtistEntity =
        resolveOrCreate(
            name = name,
            cache = artistCache,
            insertIfAbsent = { slug -> artistRepository.insertIfAbsent(name, slug) },
            findBySlug = { slug -> artistRepository.findBySlug(slug) }
        )

    // -- Artist association syncing --

    /**
     * Synchronizes artist associations for the given saved events using a diff strategy.
     *
     * Instead of deleting and re-creating all associations on every import (which wastes
     * auto-increment IDs and causes unnecessary write churn), this method compares
     * existing associations against the desired state and only:
     * - **Inserts** truly new associations (artist added to event).
     * - **Updates** associations where role or billing order changed.
     * - **Deletes** associations for artists no longer linked to the event.
     * - **Skips** associations that are already correct (no-op).
     *
     * Associations are matched by the composite key `(eventId, artistId)`.
     */
    private suspend fun syncArtistAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        artistCache: Map<String, ArtistEntity>
    ) {
        val existingByEventId =
            fetchExistingAssociationsByEventId(
                savedEvents,
                { eventArtistRepository.findByEventIdIn(it).toList() },
                EventArtistEntity::eventId
            ) ?: return

        val artistsBySourceId = scrapedEvents.associate { it.sourceId to it.artists }
        val toInsert = mutableListOf<EventArtistEntity>()
        val toUpdate = mutableListOf<EventArtistEntity>()
        val toDeleteIds = mutableListOf<Long>()

        for (saved in savedEvents) {
            val (eventId, existingByArtistId, existing) =
                eventAssociationContext(saved, existingByEventId, EventArtistEntity::artistId)

            val desiredArtists = artistsBySourceId[saved.sourceId].orEmpty()
            val desiredArtistIds = mutableSetOf<Long>()

            for ((index, scrapedArtist) in desiredArtists.withIndex()) {
                val slug = SlugGenerator.slugify(canonicalArtistName(scrapedArtist.name))
                val artistId = requireNotNull(artistCache[slug]?.id) { "Artist '$slug' must be resolved before syncing associations" }

                // `add` returns false when this artist is already desired for this event, which
                // happens whenever a lineup names one act twice or two spellings canonicalise
                // together. The billing shown is the first mention's, and skipping is what keeps the
                // pair out of `toInsert` twice — `existingByArtistId` reflects the database and
                // stays null across both passes, so it cannot catch a duplicate on its own. The
                // table's UNIQUE (event_id, artist_id) then rejects the batch and the whole run
                // rolls back (#798).
                if (!desiredArtistIds.add(artistId)) continue

                val desired = scrapedArtist.toEventArtistEntity(eventId, artistId, billingOrder = index)
                val current = existingByArtistId[artistId]
                if (current == null) {
                    toInsert.add(desired)
                } else if (current.role != desired.role ||
                    current.billingOrder != desired.billingOrder ||
                    current.stage != desired.stage
                ) {
                    toUpdate.add(current.copy(role = desired.role, billingOrder = desired.billingOrder, stage = desired.stage))
                }
            }

            existing
                .filter { it.artistId !in desiredArtistIds }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventArtistRepository.deleteAllById(toDeleteIds)
        }
        if (toUpdate.isNotEmpty()) {
            eventArtistRepository.saveAll(toUpdate).toList()
        }
        if (toInsert.isNotEmpty()) {
            eventArtistRepository.saveAll(toInsert).toList()
        }
    }

    // -- Promoter resolution --

    /**
     * Batch-fetches all known promoters by slug and auto-creates any unknown promoters.
     *
     * @return a cache mapping promoter slug → persisted [PromoterEntity], covering all
     *   promoters referenced by the scraped events.
     */
    private suspend fun resolveAllPromoters(scrapedEvents: List<ScrapedEvent>): Map<String, PromoterEntity> {
        // Drop bare generic labels ("Event.") first, then canonicalize so variants of the
        // same promoter ("LOFT", "Loft Concerts GmbH") resolve to one entity — see
        // isNonPromoterName / canonicalPromoterName.
        val canonicalNames =
            scrapedEvents
                .flatMap { it.promoters }
                .filterNot { isNonPromoterName(it) }
                .map { canonicalPromoterName(it) }
        val allPromoterSlugs = canonicalNames.map { SlugGenerator.slugify(it) }.toSet()
        if (allPromoterSlugs.isEmpty()) return emptyMap()

        val promoterCache =
            promoterRepository
                .findBySlugIn(allPromoterSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        // Auto-create only the promoters not already in the database
        canonicalNames
            .distinctBy { SlugGenerator.slugify(it) }
            .forEach { resolveOrCreatePromoter(it, promoterCache) }

        return promoterCache
    }

    /**
     * Resolves a promoter by slug (derived from its already-canonicalized [name]) from
     * the [promoterCache], or auto-creates a new promoter entity if no match is found.
     * Newly created promoters store the canonical name and are added to the cache so
     * subsequent events in the same batch can reuse them without additional DB queries.
     *
     * Handles concurrent promoter creation gracefully: if another import creates
     * the same promoter between our cache check and save, the unique constraint
     * violation on `promoter.slug` is caught and we fall back to a lookup.
     */
    private suspend fun resolveOrCreatePromoter(
        name: String,
        promoterCache: MutableMap<String, PromoterEntity>
    ): PromoterEntity =
        resolveOrCreate(
            name = name,
            cache = promoterCache,
            insertIfAbsent = { slug -> promoterRepository.insertIfAbsent(name, slug) },
            findBySlug = { slug -> promoterRepository.findBySlug(slug) }
        )

    // -- Promoter association syncing --

    /**
     * Synchronizes promoter associations for the given saved events using a diff strategy.
     *
     * Unlike artists, promoter associations have no role or ordering — they are simple
     * many-to-many links. The diff only inserts new and deletes removed associations.
     */
    private suspend fun syncPromoterAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        promoterCache: Map<String, PromoterEntity>
    ) {
        val existingByEventId =
            fetchExistingAssociationsByEventId(
                savedEvents,
                { eventPromoterRepository.findByEventIdIn(it).toList() },
                EventPromoterEntity::eventId
            ) ?: return

        // Build the desired associations from scraped data, dropping bare generic labels and
        // canonicalizing names so the slug lookup matches the (canonical) keys used when the
        // cache was populated (must mirror resolveAllPromoters' filtering exactly).
        val promotersBySourceId =
            scrapedEvents.associate { event ->
                event.sourceId to
                    event.promoters
                        .filterNot { isNonPromoterName(it) }
                        .map { canonicalPromoterName(it) }
            }
        val toInsert = mutableListOf<EventPromoterEntity>()
        val toDeleteIds = mutableListOf<Long>()

        for (saved in savedEvents) {
            val (eventId, existingByPromoterId, existing) =
                eventAssociationContext(saved, existingByEventId, EventPromoterEntity::promoterId)

            val desiredPromoterNames = promotersBySourceId[saved.sourceId].orEmpty()
            val desiredPromoterIds = mutableSetOf<Long>()

            for (promoterName in desiredPromoterNames) {
                val slug = SlugGenerator.slugify(promoterName)
                val promoterId =
                    requireNotNull(promoterCache[slug]?.id) {
                        "Promoter '$slug' must be resolved before syncing associations"
                    }

                // `add` first, and its result is the duplicate guard — see the same shape in
                // [syncArtistAssociations]. This is the one that was actually observed: a full seed
                // failed `frannz-club` outright, losing every event in the run (#798).
                if (desiredPromoterIds.add(promoterId) && existingByPromoterId[promoterId] == null) {
                    toInsert.add(EventPromoterEntity(eventId = eventId, promoterId = promoterId))
                }
            }

            existing
                .filter { it.promoterId !in desiredPromoterIds }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventPromoterRepository.deleteAllById(toDeleteIds)
        }
        if (toInsert.isNotEmpty()) {
            eventPromoterRepository.saveAll(toInsert).toList()
        }
    }

    // -- Genre tag resolution --

    /**
     * Normalizes genre strings from all scraped events into canonical genre tags,
     * batch-fetches known tags by slug, and auto-creates any unknown tags.
     *
     * @return a cache mapping genre tag slug → persisted [GenreTagEntity], covering
     *   all genre tags referenced by the scraped events.
     */
    private suspend fun resolveAllGenreTags(scrapedEvents: List<ScrapedEvent>): Map<String, GenreTagEntity> {
        val allGenreNames = scrapedEvents.flatMap { normalizeGenre(it.genre) }.distinct()
        if (allGenreNames.isEmpty()) return emptyMap()

        val allSlugs = allGenreNames.map { SlugGenerator.slugify(it) }.toSet()
        val genreTagCache =
            genreTagRepository
                .findBySlugIn(allSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        // Auto-create only the genre tags not already in the database
        allGenreNames
            .distinctBy { SlugGenerator.slugify(it) }
            .forEach { resolveOrCreateGenreTag(it, genreTagCache) }

        return genreTagCache
    }

    /**
     * Resolves a genre tag by slug (derived from name) from the [genreTagCache],
     * or auto-creates a new genre tag entity if no match is found. Newly created
     * tags are added to the cache so subsequent events in the same batch can reuse
     * them without additional database queries.
     *
     * Handles concurrent creation gracefully: if another import creates the same
     * tag between our cache check and save, the unique constraint violation on
     * `genre_tag.slug` is caught and we fall back to a lookup.
     */
    private suspend fun resolveOrCreateGenreTag(
        name: String,
        genreTagCache: MutableMap<String, GenreTagEntity>
    ): GenreTagEntity =
        resolveOrCreate(
            name = name,
            cache = genreTagCache,
            insertIfAbsent = { slug -> genreTagRepository.insertIfAbsent(name, slug) },
            findBySlug = { slug -> genreTagRepository.findBySlug(slug) }
        )

    // -- Genre tag association syncing --

    /**
     * Synchronizes genre tag associations for the given saved events using a diff strategy.
     *
     * Genre tag associations are simple many-to-many links (like promoters — no role or
     * ordering). The diff only inserts new and deletes removed associations.
     */
    private suspend fun syncGenreTagAssociations(
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

        val genresBySourceId = scrapedEvents.associate { it.sourceId to normalizeGenre(it.genre) }
        val toInsert = mutableListOf<EventGenreTagEntity>()
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

                if (existingByGenreTagId[genreTagId] == null) {
                    toInsert.add(EventGenreTagEntity(eventId = eventId, genreTagId = genreTagId))
                }
            }

            existing
                .filter { it.genreTagId !in desiredGenreTagIds }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventGenreTagRepository.deleteAllById(toDeleteIds)
        }
        if (toInsert.isNotEmpty()) {
            eventGenreTagRepository.saveAll(toInsert).toList()
        }
    }

    // -- Shared helpers for association syncing --

    /**
     * Pre-computed per-event context for diff-based association syncing.
     *
     * Avoids duplicating the eventId extraction + existing-association lookup
     * pattern across artist and promoter sync methods. Supports destructuring
     * via `val (eventId, existingByKey, existing) = ...`.
     *
     * @param T the association entity type (e.g. [EventArtistEntity], [EventPromoterEntity]).
     */
    private data class EventAssociationContext<T>(
        val eventId: Long,
        val existingByForeignKey: Map<Long, T>,
        val existing: List<T>
    )

    /**
     * Batch-fetches existing associations for all saved events and groups them by event ID.
     *
     * Centralizes the savedEventIds extraction + early-return guard + batch-fetch pattern
     * shared by [syncArtistAssociations] and [syncPromoterAssociations].
     *
     * @return grouped associations, or `null` if no saved events have IDs (caller should
     *   early-return in that case).
     */
    private suspend fun <T> fetchExistingAssociationsByEventId(
        savedEvents: List<EventEntity>,
        fetchByEventIds: suspend (List<Long>) -> List<T>,
        getEventId: (T) -> Long
    ): Map<Long, List<T>>? {
        val savedEventIds = savedEvents.mapNotNull { it.id }
        if (savedEventIds.isEmpty()) return null
        return fetchByEventIds(savedEventIds).groupBy(getEventId)
    }

    /**
     * Builds the per-event association context for diff-based syncing: extracts the
     * event ID, retrieves existing associations, and indexes them by foreign key.
     */
    private fun <T> eventAssociationContext(
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

    // -- Generic resolve-or-create for slug-based entities --

    /**
     * Generic resolve-or-create logic for slug-based entities (artists, promoters, genre tags).
     *
     * Checks the [cache] first, then issues a conflict-tolerant `INSERT … ON CONFLICT DO NOTHING`
     * via [insertIfAbsent] and reads the row back via [findBySlug]. The resolved entity is always
     * added to [cache] for batch reuse.
     *
     * **Why not try-`save`-then-catch-and-reselect:** these resolutions run inside the caller's
     * single import transaction, and imports run concurrently (`EventImportService.importConcurrently`)
     * racing to insert the same shared slug (a common genre, a co-billed artist). In PostgreSQL a
     * failed statement aborts the *whole* transaction, so catching the unique-violation and then
     * re-querying in the same transaction fails with "current transaction is aborted". `ON CONFLICT
     * DO NOTHING` never raises, so the transaction stays valid: a lost race becomes a brief
     * index-lock wait and a `0`-row no-op, after which [findBySlug] returns the winner's row.
     *
     * The entity-type label for the log line is taken from the resolved entity's class name.
     *
     * @param T the entity type.
     * @param name the human-readable name to slugify and resolve.
     * @param cache mutable slug → entity map shared across the batch.
     * @param insertIfAbsent conflict-tolerant insert; returns rows inserted (`1` created, `0` already present).
     * @param findBySlug fetches the entity by slug (always present after [insertIfAbsent]).
     */
    private suspend fun <T : Any> resolveOrCreate(
        name: String,
        cache: MutableMap<String, T>,
        insertIfAbsent: suspend (slug: String) -> Int,
        findBySlug: suspend (slug: String) -> T?
    ): T {
        val slug = SlugGenerator.slugify(name)
        cache[slug]?.let { return it }

        val created = insertIfAbsent(slug) == 1
        val entity =
            findBySlug(slug)
                ?: throw IllegalStateException("Entity with slug '$slug' not found after insert-if-absent")

        if (created) {
            logger.info { "Auto-created ${entity::class.simpleName} '$name' (slug=$slug)" }
        } else {
            logger.debug { "Reused existing ${entity::class.simpleName} (slug=$slug)" }
        }
        cache[slug] = entity
        return entity
    }
}

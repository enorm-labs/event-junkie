package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.canonicalArtistName
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventType
import de.norm.events.genretag.EventGenreTagEntity
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagEntity
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.genreFamily
import de.norm.events.genretag.normalizeGenre
import de.norm.events.promoter.PromoterEntity
import de.norm.events.promoter.PromoterRepository
import de.norm.events.promoter.canonicalPromoterName
import de.norm.events.promoter.isNonPromoterName
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.net.URI

/**
 * Resolves artists, promoters and genre tags by slug, auto-creating unknown ones, and
 * synchronizes the join-table associations for upserted events by diff: insert new, update
 * changed (artist role/billing order), delete stale. Called within the caller's transaction.
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
     * The single entry point [EventUpsertService] calls after upserting: resolve artists, diff-sync
     * their associations, the same for promoters, then normalized genre tags.
     *
     * @param savedEvents the persisted event entities (non-null IDs).
     * @param scrapedEvents the raw scraped events.
     * @return the ids of every artist row this run billed, new or existing, for the MusicBrainz
     * sweep after the commit (#1567).
     */
    suspend fun resolveAndSyncAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>
    ): Set<Long> {
        val artistCache = resolveAllArtists(scrapedEvents)
        syncArtistAssociations(savedEvents, scrapedEvents, artistCache)

        val promoterCache = resolveAllPromoters(scrapedEvents)
        syncPromoterAssociations(savedEvents, scrapedEvents, promoterCache)

        val genreTagCache = resolveAllGenreTags(scrapedEvents)
        syncGenreTagAssociations(savedEvents, scrapedEvents, genreTagCache)
        return artistCache.values.mapNotNullTo(mutableSetOf()) { it.id }
    }

    // -- Artist resolution --

    /**
     * Batch-fetches known artists by slug and auto-creates the rest.
     *
     * @return artist slug to persisted [ArtistEntity] for every artist the scraped events reference.
     */
    private suspend fun resolveAllArtists(scrapedEvents: List<ScrapedEvent>): Map<String, ArtistEntity> {
        // Canonicalize before slugging, as the promoter path does: a curated NAME_CORRECTIONS entry can
        // change the slug ("OXO86" resolves to "Oxo 86", `oxo-86`), and slugging the raw name would look
        // up a different row than resolveOrCreateArtist creates.
        scrapedEvents.forEach { event ->
            event.artists
                .filter { isSlugless(it.name) }
                .forEach { logger.warn { "Dropping artist '${it.name}' of '${event.sourceId}': its name slugs to nothing" } }
        }
        val scrapedArtists = scrapedEvents.flatMap { it.storableArtists() }
        val allArtistSlugs = scrapedArtists.map { SlugGenerator.slugify(canonicalArtistName(it.name)) }.toSet()
        val artistCache =
            artistRepository
                .findBySlugIn(allArtistSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        // Auto-create only the artists not in the database, with the display name canonicalized first so
        // an act is not frozen SHOUTING by whichever venue imported it first.
        scrapedArtists
            .distinctBy { SlugGenerator.slugify(canonicalArtistName(it.name)) }
            .forEach { resolveOrCreateArtist(canonicalArtistName(it.name), artistCache) }

        return artistCache
    }

    /**
     * The scraped artists that can be stored, with the suffix an act does not own taken off its
     * name once, here, for every source: `C3D-E (live)`, `Avangelic (DJ-Set)` and `Regis Live & DJ
     * set` are the same rows as `C3D-E`, `Avangelic` and `Regis` imported from anywhere else, and
     * sixteen line-up scrapers never called [stripArtistSuffix] (#301). The model keeps no format,
     * so nothing is lost that could have been stored. A name that slugs to nothing has escaped
     * [isNonArtistName], and would take the empty slug every later one collides with (#1553). A
     * headliner read off a title the boundary resolves to a festival is the festival's name, not
     * an act (`ELLE & L's Festival` → `Elle`, #300): undone here, once, while a published line-up stays.
     */
    private fun ScrapedEvent.storableArtists(): List<ScrapedArtist> {
        val festival = resolvedEventType() == EventType.FESTIVAL
        return artists
            .map { it.copy(name = stripArtistSuffix(it.name)) }
            .filterNot { isSlugless(it.name) || isNonArtistName(it.name) || (festival && it.titleDerived) }
    }

    /** Resolves an artist by name from [artistCache], or auto-creates one. See [resolveOrCreate]. */
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
     * Synchronizes artist associations by diff, matched on `(eventId, artistId)`: inserts new,
     * updates changed role or billing order, deletes removed, skips the rest. Deleting and
     * re-creating on every import wastes auto-increment IDs.
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

        val artistsBySourceId = scrapedEvents.associate { it.sourceId to it.storableArtists() }
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

                // `add` returns false when this artist is already desired for this event, as when a lineup
                // names one act twice or two spellings canonicalise together. `existingByArtistId` reflects the
                // database and cannot catch that; the table's UNIQUE (event_id, artist_id) would reject the
                // batch and roll back the whole run (#798).
                if (!desiredArtistIds.add(artistId)) continue

                val desired = scrapedArtist.toEventArtistEntity(eventId, artistId, billingOrder = index)
                val current = existingByArtistId[artistId]
                if (current == null) {
                    toInsert.add(desired)
                } else {
                    // The row keeps its id; everything the scrape decides is compared at once.
                    val updated = desired.copy(id = current.id)
                    if (updated != current) toUpdate.add(updated)
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
     * Batch-fetches known promoters by slug and auto-creates the rest.
     *
     * @return promoter slug to persisted [PromoterEntity].
     */
    private suspend fun resolveAllPromoters(scrapedEvents: List<ScrapedEvent>): Map<String, PromoterEntity> {
        // Drop bare generic labels ("Event.") first, then canonicalize so "LOFT" and "Loft Concerts
        // GmbH" resolve to one entity (isNonPromoterName / canonicalPromoterName).
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

        fillPromoterWebsites(scrapedEvents, promoterCache)
        return promoterCache
    }

    /**
     * Writes the website a venue links a promoter credit to onto a promoter row that has none.
     * Fill-if-empty, never replace: the venue's link is as often the promoter's ticket shop, and a
     * reviewed `website_url` (docs/promoters/REVIEWED.tsv) must not lose to it (#1319). A link back
     * onto the venue's own host is the event page (#1362).
     */
    private suspend fun fillPromoterWebsites(
        scrapedEvents: List<ScrapedEvent>,
        promoterCache: MutableMap<String, PromoterEntity>
    ) {
        val websitesBySlug =
            scrapedEvents
                .flatMap { event -> event.promoterWebsites.entries.map { (raw, url) -> Triple(raw, url, event.sourceUrl) } }
                .filterNot { (raw, url, sourceUrl) -> isNonPromoterName(raw) || url.hostOrNull() == sourceUrl.hostOrNull() }
                .associate { (raw, url, _) -> SlugGenerator.slugify(canonicalPromoterName(raw)) to url }
        websitesBySlug
            .mapNotNull { (slug, url) -> promoterCache[slug]?.takeIf { it.websiteUrl == null }?.let { it to url } }
            .forEach { (promoter, url) ->
                promoterCache[promoter.slug] = promoterRepository.save(promoter.copy(websiteUrl = url))
                logger.info { "Filled website of promoter '${promoter.slug}' from the venue's credit: $url" }
            }
    }

    /**
     * Resolves a promoter by its canonicalized [name] from [promoterCache], or auto-creates one. See
     * [resolveOrCreate].
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
     * Synchronizes promoter associations by diff: simple many-to-many links, so insert and delete only.
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

        // The desired associations from scraped data, filtered and canonicalized exactly as
        // resolveAllPromoters populated the cache.
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

                // `add` first, and its result is the duplicate guard, as in [syncArtistAssociations]. This is the
                // one observed: a full seed failed `frannz-club` outright (#798).
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
     * Normalizes genre strings into canonical tags, batch-fetches known tags by slug and
     * auto-creates the rest.
     *
     * @return genre tag slug to persisted [GenreTagEntity].
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

    /** Resolves a genre tag by name from [genreTagCache], or auto-creates one. See [resolveOrCreate]. */
    private suspend fun resolveOrCreateGenreTag(
        name: String,
        genreTagCache: MutableMap<String, GenreTagEntity>
    ): GenreTagEntity =
        resolveOrCreate(
            name = name,
            cache = genreTagCache,
            insertIfAbsent = { slug -> genreTagRepository.insertIfAbsent(name, slug, genreFamily(slug)?.slug) },
            findBySlug = { slug -> genreTagRepository.findBySlug(slug) }
        )

    // -- Genre tag association syncing --

    /**
     * Synchronizes genre tag associations by diff: simple links, insert and delete only.
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
     * Pre-computed per-event context for diff syncing, shared by the artist and promoter methods;
     * supports destructuring.
     *
     * @param T the association entity type.
     */
    private data class EventAssociationContext<T>(
        val eventId: Long,
        val existingByForeignKey: Map<Long, T>,
        val existing: List<T>
    )

    /**
     * Batch-fetches existing associations for all saved events, grouped by event ID.
     *
     * @return grouped associations, or `null` if no saved events have IDs.
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
     * The per-event association context: event ID, existing associations, indexed by foreign key.
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
                ?: error("Entity with slug '$slug' not found after insert-if-absent")

        if (created) {
            logger.info { "Auto-created ${entity::class.simpleName} '$name' (slug=$slug)" }
        } else {
            logger.debug { "Reused existing ${entity::class.simpleName} (slug=$slug)" }
        }
        cache[slug] = entity
        return entity
    }
}

/** The host of a URL, lower-cased and without a `www.` prefix; null where the string is not a URL. */
private fun String.hostOrNull(): String? = runCatching { URI(this).host?.lowercase()?.removePrefix("www.") }.getOrNull()

package de.norm.events.event

import de.norm.events.artist.ArtistNotFoundException
import de.norm.events.artist.ArtistRepository
import de.norm.events.common.PageResponse
import de.norm.events.genretag.EventGenreTagEntity
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagEntity
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.normalizeGenre
import de.norm.events.promoter.PromoterNotFoundException
import de.norm.events.promoter.PromoterRepository
import de.norm.events.slug.SlugGenerator
import de.norm.events.venue.VenueNotFoundException
import de.norm.events.venue.VenueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service encapsulating event business logic.
 *
 * Events are the core entity linking venues, artists, and promoters.
 * Create and update operations are transactional because they span multiple
 * tables (`event`, `event_artist`, `event_promoter`). Slugs are auto-generated
 * from the event date and title using [SlugGenerator] when not provided.
 */
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val eventArtistRepository: EventArtistRepository,
    private val eventPromoterRepository: EventPromoterRepository,
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val genreTagRepository: GenreTagRepository,
    private val venueRepository: VenueRepository,
    private val artistRepository: ArtistRepository,
    private val promoterRepository: PromoterRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists events with their artist, promoter, and genre tag associations, applying pagination and sorting.
     *
     * Pagination and sorting are controlled by the [pageable] parameter, which Spring
     * resolves from `page`, `size`, and `sort` query parameters
     * (e.g. `?page=0&size=20&sort=eventDate,desc`).
     *
     * Uses batch loading to avoid N+1 queries: fetches the current page of events first,
     * then bulk-fetches all artist, promoter, and genre tag associations for that page in
     * 3 additional queries. This results in exactly 4 queries per page regardless
     * of the number of events.
     */
    @Transactional(readOnly = true)
    suspend fun findAll(pageable: Pageable): PageResponse<EventResponse> {
        val total = eventRepository.count()
        val entities = eventRepository.findAllBy(pageable).toList()
        if (entities.isEmpty()) return PageResponse.of(emptyList(), pageable, total)

        val eventIds = entities.map { requireNotNull(it.id) { "Persisted event must have an ID" } }

        // Batch-fetch all associations in 3 queries, grouped by event ID
        val artistsByEventId = eventArtistRepository.findByEventIdIn(eventIds).toList().groupBy { it.eventId }
        val promotersByEventId = eventPromoterRepository.findByEventIdIn(eventIds).toList().groupBy { it.eventId }
        val genreTagsByEventId = eventGenreTagRepository.findByEventIdIn(eventIds).toList().groupBy { it.eventId }

        // Resolve genre tag IDs to names in a single batch query
        val allGenreTagIds =
            genreTagsByEventId.values
                .flatten()
                .map { it.genreTagId }
                .distinct()
        val genreTagNamesById =
            if (allGenreTagIds.isNotEmpty()) {
                genreTagRepository.findAllById(allGenreTagIds).toList().associate { it.id!! to it.name }
            } else {
                emptyMap()
            }

        val content =
            entities.map { entity ->
                val artists = artistsByEventId[entity.id]?.map { EventArtistResponse.fromEntity(it) } ?: emptyList()
                val promoters = promotersByEventId[entity.id]?.map { it.promoterId } ?: emptyList()
                val genreTags = genreTagsByEventId[entity.id]?.mapNotNull { genreTagNamesById[it.genreTagId] } ?: emptyList()

                toResponse(entity, artists, promoters, genreTags)
            }
        return PageResponse.of(content, pageable, total)
    }

    /**
     * Finds a single event by [id], fully assembled with artist and promoter associations.
     *
     * @throws EventNotFoundException if no event with the given [id] exists.
     */
    @Transactional(readOnly = true)
    suspend fun findById(id: Long): EventResponse {
        val entity = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        return toResponse(entity)
    }

    /**
     * Creates a new event with its artist and promoter associations.
     *
     * Validates that the referenced venue, artists, and promoters exist before
     * persisting. Slug is auto-generated from date and title if not provided
     * (e.g. `"2026-06-12-the-adicts"`).
     *
     * @throws VenueNotFoundException if the referenced venue does not exist.
     * @throws ArtistNotFoundException if any referenced artist does not exist.
     * @throws PromoterNotFoundException if any referenced promoter does not exist.
     */
    @Transactional
    suspend fun create(request: EventRequest): EventResponse {
        // Validate that referenced venue exists and retrieve its slug for event slug generation
        val venue = venueRepository.findById(request.venueId) ?: throw VenueNotFoundException(request.venueId)

        // Include venue slug in event slug to ensure cross-venue uniqueness for events
        // with the same title on the same date (e.g. "Open Decks" at two different venues).
        val slug = SlugGenerator.slugify("${request.eventDate}-${venue.slug}-${request.title}")
        val saved = eventRepository.save(request.toEventEntity(slug))
        val eventId = saved.id!!

        val artistResponses = saveArtistAssociations(eventId, request.artists)
        val promoterIdResponses = savePromoterAssociations(eventId, request.promoterIds)
        val genreTagNames = saveGenreTagAssociations(eventId, request.genre)

        logger.info { "Created event '${saved.title}' with id $eventId" }
        return toResponse(saved, artistResponses, promoterIdResponses, genreTagNames)
    }

    /**
     * Replaces all fields and associations of an existing event.
     *
     * Artist and promoter associations are replaced using a delete-and-reinsert
     * strategy within the same transaction.
     *
     * @throws EventNotFoundException if no event with the given [id] exists.
     * @throws VenueNotFoundException if the referenced venue does not exist.
     * @throws ArtistNotFoundException if any referenced artist does not exist.
     * @throws PromoterNotFoundException if any referenced promoter does not exist.
     */
    @Transactional
    suspend fun update(
        id: Long,
        request: EventRequest
    ): EventResponse {
        val existing = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        val venue = venueRepository.findById(request.venueId) ?: throw VenueNotFoundException(request.venueId)

        val slug = SlugGenerator.slugify("${request.eventDate}-${venue.slug}-${request.title}")
        // Remap all request fields via the shared factory, then carry over the identity and
        // audit fields the request never owns (primary key, import source, creation timestamp).
        val updated =
            request.toEventEntity(slug).copy(
                id = existing.id,
                eventSourceId = existing.eventSourceId,
                createdAt = existing.createdAt
            )
        val saved = eventRepository.save(updated)

        // Replace artist associations: delete existing, insert new
        eventArtistRepository.deleteByEventId(id)
        val artistResponses = saveArtistAssociations(id, request.artists)

        // Replace promoter associations: delete existing, insert new
        eventPromoterRepository.deleteByEventId(id)
        val promoterIdResponses = savePromoterAssociations(id, request.promoterIds)

        // Replace genre tag associations: delete existing, insert new
        eventGenreTagRepository.deleteByEventId(id)
        val genreTagNames = saveGenreTagAssociations(id, request.genre)

        logger.info { "Updated event '${saved.title}' (id=$id)" }
        return toResponse(saved, artistResponses, promoterIdResponses, genreTagNames)
    }

    /**
     * Deletes an event and its associations by [id].
     *
     * @throws EventNotFoundException if no event with the given [id] exists.
     */
    @Transactional
    suspend fun delete(id: Long) {
        if (!eventRepository.existsById(id)) throw EventNotFoundException(id)
        // Join table rows (event_artist, event_promoter, event_genre_tag) are cascade-deleted
        // by the database FK constraints (ON DELETE CASCADE).
        eventRepository.deleteById(id)
        logger.info { "Deleted event with id $id" }
    }

    /**
     * Validates that all referenced artists exist, persists the associations,
     * and returns the corresponding [EventArtistResponse] list for the caller.
     *
     * Uses batch validation via [findAllById] to check all artist IDs in a single query
     * instead of validating each one sequentially.
     */
    private suspend fun saveArtistAssociations(
        eventId: Long,
        artists: List<EventArtistRequest>
    ): List<EventArtistResponse> {
        if (artists.isEmpty()) return emptyList()

        // Batch-validate all referenced artists in a single query
        val requestedIds = artists.map { it.artistId }.toSet()
        val existingIds =
            artistRepository
                .findAllById(requestedIds)
                .toList()
                .map { it.id!! }
                .toSet()
        val missingIds = requestedIds - existingIds
        if (missingIds.isNotEmpty()) throw ArtistNotFoundException(missingIds.first())

        // Batch-persist all associations in a single saveAll call
        val entities =
            artists.map { artistReq ->
                EventArtistEntity(
                    eventId = eventId,
                    artistId = artistReq.artistId,
                    role = artistReq.role.name,
                    billingOrder = artistReq.billingOrder,
                    stage = artistReq.stage
                )
            }
        eventArtistRepository.saveAll(entities).toList()

        return artists.map {
            EventArtistResponse(artistId = it.artistId, role = it.role, billingOrder = it.billingOrder, stage = it.stage)
        }
    }

    /**
     * Validates that all referenced promoters exist, persists the associations,
     * and returns the promoter IDs for the caller.
     *
     * Uses batch validation via [findAllById] to check all promoter IDs in a single query
     * instead of validating each one sequentially.
     */
    private suspend fun savePromoterAssociations(
        eventId: Long,
        promoterIds: List<Long>
    ): List<Long> {
        if (promoterIds.isEmpty()) return emptyList()

        // Batch-validate all referenced promoters in a single query
        val requestedIds = promoterIds.toSet()
        val existingIds =
            promoterRepository
                .findAllById(requestedIds)
                .toList()
                .map { it.id!! }
                .toSet()
        val missingIds = requestedIds - existingIds
        if (missingIds.isNotEmpty()) throw PromoterNotFoundException(missingIds.first())

        // Batch-persist all associations in a single saveAll call
        val entities =
            promoterIds.map { promoterId ->
                EventPromoterEntity(eventId = eventId, promoterId = promoterId)
            }
        eventPromoterRepository.saveAll(entities).toList()

        return promoterIds
    }

    /**
     * Normalizes the raw genre string into canonical genre tags, resolves or creates
     * the tags in the database, and persists the join-table associations.
     *
     * Uses the same [normalizeGenre] function as the scraper pipeline to ensure
     * consistent genre normalization between manual creation and imports.
     *
     * @return the list of canonical genre tag names for the response.
     */
    private suspend fun saveGenreTagAssociations(
        eventId: Long,
        rawGenre: String?
    ): List<String> {
        val genreNames = normalizeGenre(rawGenre)
        if (genreNames.isEmpty()) return emptyList()

        // Resolves each genre tag individually (N+1) — deliberately simpler than the batch-fetch
        // + cache strategy in AssociationSyncService, since the admin API handles single-event
        // operations with very few genre tags (typically 1–5).
        val entities =
            genreNames.map { name ->
                val slug = SlugGenerator.slugify(name)
                val tag =
                    genreTagRepository.findBySlug(slug)
                        ?: genreTagRepository.save(
                            GenreTagEntity(name = name, slug = slug)
                        )
                EventGenreTagEntity(
                    eventId = eventId,
                    genreTagId = requireNotNull(tag.id) { "GenreTag must be persisted before creating association" }
                )
            }
        eventGenreTagRepository.saveAll(entities).toList()

        return genreNames
    }

    /**
     * Resolves an [EventEntity]'s artist, promoter, and genre tag associations and delegates
     * to [EventResponse.fromEntity] for the actual mapping.
     *
     * When [artistResponses], [promoterIds], and [genreTagNames] are provided (e.g. after
     * create/update), they are used directly to avoid re-querying within the same transaction.
     */
    private suspend fun toResponse(
        entity: EventEntity,
        artistResponses: List<EventArtistResponse>? = null,
        promoterIds: List<Long>? = null,
        genreTagNames: List<String>? = null
    ): EventResponse {
        val artists =
            artistResponses ?: eventArtistRepository.findByEventId(entity.id!!).toList().map { EventArtistResponse.fromEntity(it) }
        val promoters = promoterIds ?: eventPromoterRepository.findByEventId(entity.id!!).toList().map { it.promoterId }
        val genreTags =
            genreTagNames ?: run {
                val tagAssociations = eventGenreTagRepository.findByEventId(entity.id!!).toList()
                if (tagAssociations.isEmpty()) {
                    emptyList()
                } else {
                    val tagIds = tagAssociations.map { it.genreTagId }
                    genreTagRepository.findAllById(tagIds).toList().map { it.name }
                }
            }

        return EventResponse.fromEntity(entity, artists, promoters, genreTags)
    }
}

/**
 * Maps an [EventRequest] and pre-computed [slug] onto a new [EventEntity].
 *
 * Single source of truth for the request → entity field mapping, shared by
 * [EventService.create] (persists the result directly) and [EventService.update]
 * (copies the identity and audit fields from the existing row onto the result).
 * Monetary values are normalized to scale 2 at this boundary.
 */
private fun EventRequest.toEventEntity(slug: String): EventEntity =
    EventEntity(
        venueId = venueId,
        title = title,
        subtitle = subtitle,
        description = description,
        eventType = eventType.name,
        status = status.name,
        slug = slug,
        eventDate = eventDate,
        doorsTime = doorsTime,
        startTime = startTime,
        imageUrl = imageUrl,
        sourceUrl = sourceUrl,
        sourceId = sourceId,
        ticketUrl = ticketUrl,
        facebookEventUrl = facebookEventUrl,
        genre = genre,
        pricePresale = pricePresale?.normalizeMoneyScale(),
        priceBoxOffice = priceBoxOffice?.normalizeMoneyScale(),
        priceCurrency = priceCurrency,
        priceNote = priceNote,
        soldOut = soldOut,
        free = free
    )

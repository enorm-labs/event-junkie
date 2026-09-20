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
 * Event business logic. Create and update are transactional because they span `event`,
 * `event_artist` and `event_promoter`; slugs come from [SlugGenerator] when not provided.
 */
@Service
@Suppress("LongParameterList") // Constructor injection: one parameter per collaborator; splitting the service hides the wiring.
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
     * Lists events with their associations, paged and sorted by [pageable]. Batch loading: the page
     * of events first, then artists, promoters and genre tags in three more queries, four per page
     * regardless of size.
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
                genreTagRepository.findAllById(allGenreTagIds).toList().associate { it.requiredId() to it.name }
            } else {
                emptyMap()
            }

        val content =
            entities.map { entity ->
                val artists = artistsByEventId[entity.id]?.map { EventArtistResponse.fromEntity(it) }.orEmpty()
                val promoters = promotersByEventId[entity.id]?.map { it.promoterId }.orEmpty()
                val genreTags = genreTagsByEventId[entity.id]?.mapNotNull { genreTagNamesById[it.genreTagId] }.orEmpty()

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
     * Creates a new event with its associations, validating that the venue, artists and promoters
     * exist. Slug auto-generated from date and title if not provided.
     *
     * @throws VenueNotFoundException if the referenced venue does not exist.
     * @throws ArtistNotFoundException if any referenced artist does not exist.
     * @throws PromoterNotFoundException if any referenced promoter does not exist.
     */
    @Transactional
    suspend fun create(request: EventRequest): EventResponse {
        // Validate that referenced venue exists and retrieve its slug for event slug generation
        val venue = venueRepository.findById(request.venueId) ?: throw VenueNotFoundException(request.venueId)

        // The venue slug in the event slug, for "Open Decks" at two venues on one date.
        val slug = SlugGenerator.slugify("${request.eventDate}-${venue.slug}-${request.title}")
        val saved = eventRepository.save(request.toEventEntity(slug))
        val eventId = requireNotNull(saved.id) { "Persisted event must have an ID" }

        val artistResponses = saveArtistAssociations(eventId, request.artists)
        val promoterIdResponses = savePromoterAssociations(eventId, request.promoterIds)
        val genreTagNames = saveGenreTagAssociations(eventId, request.genre)

        logger.info { "Created event '${saved.title}' with id $eventId" }
        return toResponse(saved, artistResponses, promoterIdResponses, genreTagNames)
    }

    /**
     * Classifies the language of every stored description that carries none. A one-off for rows
     * that predate detection; a text the classifier cannot call keeps a null language (ADR-026).
     * Idempotent.
     */
    @Transactional
    suspend fun classifyStoredDescriptions(): DescriptionLanguageBackfill {
        // Read the whole page before writing any of it: a `save` issued from inside `collect` runs on
        // the connection the open cursor holds, and the two wait on each other until the request times
        // out with nothing in the log. The translation pass materialises first for the same reason.
        val unclassified = eventRepository.findWithUnclassifiedDescription().toList()
        val detections = unclassified.map { it to DescriptionLanguage.detect(it.description) }

        detections.forEach { (event, detected) ->
            if (detected != null) {
                eventRepository.save(
                    event.copy(descriptionLanguage = detected.language.code, descriptionLanguageConfidence = detected.confidence)
                )
            }
        }

        val german = detections.count { it.second?.language == DescriptionLanguage.GERMAN }
        val english = detections.count { it.second?.language == DescriptionLanguage.ENGLISH }
        val unknown = detections.count { it.second == null }
        logger.info { "Classified stored descriptions: german=$german english=$english unknown=$unknown" }
        return DescriptionLanguageBackfill(german = german, english = english, unknown = unknown)
    }

    /**
     * Replaces all fields and associations of an existing event, delete-and-reinsert within one
     * transaction.
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
        // Remap via the shared factory, then carry over the identity and audit fields the request never
        // owns.
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
        // Join table rows are cascade-deleted by the FK constraints.
        eventRepository.deleteById(id)
        logger.info { "Deleted event with id $id" }
    }

    /**
     * Validates that all referenced artists exist in one [findAllById] query, persists the
     * associations, and returns the [EventArtistResponse] list.
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
                .map { requireNotNull(it.id) { "Persisted artist must have an ID" } }
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
     * Validates that all referenced promoters exist in one [findAllById] query, persists the
     * associations, and returns the promoter IDs.
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
                .map { requireNotNull(it.id) { "Persisted promoter must have an ID" } }
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
     * Normalizes the raw genre string into canonical tags with the scraper pipeline's
     * [normalizeGenre], resolves or creates them, and persists the associations.
     *
     * @return the canonical genre tag names for the response.
     */
    private suspend fun saveGenreTagAssociations(
        eventId: Long,
        rawGenre: String?
    ): List<String> {
        val genreNames = normalizeGenre(rawGenre)
        if (genreNames.isEmpty()) return emptyList()

        // Resolves each genre tag individually (N+1), deliberately simpler than
        // AssociationSyncService's batch strategy: the admin API handles one event with 1–5 tags.
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
     * Resolves an [EventEntity]'s associations and delegates to [EventResponse.fromEntity]. When
     * [artistResponses], [promoterIds] and [genreTagNames] are provided, they avoid re-querying
     * within the same transaction.
     */
    private suspend fun toResponse(
        entity: EventEntity,
        artistResponses: List<EventArtistResponse>? = null,
        promoterIds: List<Long>? = null,
        genreTagNames: List<String>? = null
    ): EventResponse {
        val eventId = requireNotNull(entity.id) { "Persisted event must have an ID" }
        val artists =
            artistResponses ?: eventArtistRepository.findByEventId(eventId).toList().map { EventArtistResponse.fromEntity(it) }
        val promoters = promoterIds ?: eventPromoterRepository.findByEventId(eventId).toList().map { it.promoterId }
        val genreTags =
            genreTagNames ?: run {
                val tagAssociations = eventGenreTagRepository.findByEventId(eventId).toList()
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
 * Maps an [EventRequest] and pre-computed [slug] onto a new [EventEntity]: the one request-to-
 * entity mapping, shared by [EventService.create] and [EventService.update]. Monetary values
 * are normalized to scale 2 here.
 */
private fun EventRequest.toEventEntity(slug: String): EventEntity {
    val detected = DescriptionLanguage.detect(description)
    return EventEntity(
        venueId = venueId,
        title = title,
        subtitle = subtitle,
        description = description,
        // Detected here as well as in the scraper path, so a hand-created event is marked the same way.
        descriptionLanguage = detected?.language?.code,
        descriptionLanguageConfidence = detected?.confidence,
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
}

/** The id of a genre tag read back from the database, which is never null once it is persisted. */
private fun GenreTagEntity.requiredId(): Long = requireNotNull(id) { "Persisted genre tag must have an ID" }

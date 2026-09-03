package de.norm.events.venue

import de.norm.events.common.PageResponse
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * Service encapsulating venue business logic.
 *
 * All methods are suspending to align with the reactive R2DBC stack.
 * Slugs are always auto-generated from the venue name using [SlugGenerator].
 */
@Service
class VenueService(
    private val venueRepository: VenueRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists venues with pagination and sorting.
     *
     * Pagination and sorting are controlled by the [pageable] parameter, which Spring
     * resolves from `page`, `size`, and `sort` query parameters
     * (e.g. `?page=0&size=20&sort=name,asc`).
     *
     * The total comes from a separate `count()`, which is exact because this listing takes no
     * filter. It is what lets a caller tell one page from the whole table (#810).
     */
    suspend fun findAll(pageable: Pageable): PageResponse<VenueResponse> =
        PageResponse.of(
            venueRepository.findAllBy(pageable).map { VenueResponse.fromDomain(it.toDomain()) }.toList(),
            pageable,
            venueRepository.count()
        )

    /**
     * Finds a single venue by [id].
     *
     * @throws VenueNotFoundException if no venue with the given [id] exists.
     */
    suspend fun findById(id: Long): VenueResponse = VenueResponse.fromDomain(venueRepository.findById(id)?.toDomain() ?: throw VenueNotFoundException(id))

    /**
     * Creates a new venue.
     *
     * The slug is auto-generated from the venue name
     * (e.g. `"Astra Kulturhaus"` → `"astra-kulturhaus"`).
     */
    suspend fun create(request: VenueRequest): VenueResponse {
        val slug = SlugGenerator.slugify(request.name)
        // Pre-check for slug uniqueness to provide a clear error message instead of
        // relying on the DB constraint, which would produce a generic 409 response.
        ensureSlugAvailable(request.name, slug)

        // Route through the domain model to ensure any future business rules
        // (e.g. validation, normalization) are consistently applied (see ADR-003).
        val venue =
            Venue(
                name = request.name,
                slug = slug,
                address = request.address,
                city = request.city,
                postalCode = request.postalCode,
                district = request.district?.value,
                latitude = request.latitude,
                longitude = request.longitude,
                websiteUrl = request.websiteUrl,
                imageUrl = request.imageUrl,
                description = request.description
            )
        val entity = VenueEntity.fromDomain(venue)
        val saved = venueRepository.save(entity)
        logger.info { "Created venue '${saved.name}' with id ${saved.id}" }
        return VenueResponse.fromDomain(saved.toDomain())
    }

    /**
     * Replaces all mutable fields of an existing venue.
     *
     * @throws VenueNotFoundException if no venue with the given [id] exists.
     */
    suspend fun update(
        id: Long,
        request: VenueRequest
    ): VenueResponse {
        val existing =
            venueRepository.findById(id)
                ?: throw VenueNotFoundException(id)

        val slug = SlugGenerator.slugify(request.name)
        // Only check for conflicts if the slug actually changed — renaming to the same
        // effective slug (e.g. fixing capitalization) should not trigger a false positive.
        if (slug != existing.slug) {
            ensureSlugAvailable(request.name, slug)
        }

        val updated =
            existing.copy(
                name = request.name,
                slug = slug,
                address = request.address,
                city = request.city,
                postalCode = request.postalCode,
                district = request.district?.value,
                latitude = request.latitude,
                longitude = request.longitude,
                websiteUrl = request.websiteUrl,
                imageUrl = request.imageUrl,
                description = request.description
            )
        val saved = venueRepository.save(updated)
        logger.info { "Updated venue '${saved.name}' (id=${saved.id})" }
        return VenueResponse.fromDomain(saved.toDomain())
    }

    /**
     * Deletes a venue by [id].
     *
     * @throws VenueNotFoundException if no venue with the given [id] exists.
     */
    suspend fun delete(id: Long) {
        if (!venueRepository.existsById(id)) throw VenueNotFoundException(id)
        venueRepository.deleteById(id)
        logger.info { "Deleted venue with id $id" }
    }

    /**
     * Checks whether the given [slug] is already taken by another venue and throws
     * [DuplicateVenueSlugException] if so. This provides a clear, domain-specific error
     * message to the API consumer instead of relying on the DB's generic constraint violation.
     */
    private suspend fun ensureSlugAvailable(
        name: String,
        slug: String
    ) {
        if (venueRepository.findBySlug(slug) != null) {
            throw DuplicateVenueSlugException(name, slug)
        }
    }
}

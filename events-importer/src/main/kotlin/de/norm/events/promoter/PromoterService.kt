package de.norm.events.promoter

import de.norm.events.common.PageResponse
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * Service encapsulating promoter business logic.
 *
 * Slugs are always auto-generated from the promoter name using [SlugGenerator].
 */
@Service
class PromoterService(
    private val promoterRepository: PromoterRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists promoters with pagination and sorting.
     *
     * Pagination and sorting are controlled by the [pageable] parameter, which Spring
     * resolves from `page`, `size`, and `sort` query parameters
     * (e.g. `?page=0&size=20&sort=name,asc`).
     *
     * The total comes from a separate `count()`, which is exact because this listing takes no
     * filter. It is what lets a caller tell one page from the whole table (#810).
     */
    suspend fun findAll(pageable: Pageable): PageResponse<PromoterResponse> =
        PageResponse.of(
            promoterRepository.findAllBy(pageable).map { PromoterResponse.fromDomain(it.toDomain()) }.toList(),
            pageable,
            promoterRepository.count()
        )

    /**
     * Finds a single promoter by [id].
     *
     * @throws PromoterNotFoundException if no promoter with the given [id] exists.
     */
    suspend fun findById(id: Long): PromoterResponse =
        PromoterResponse.fromDomain(promoterRepository.findById(id)?.toDomain() ?: throw PromoterNotFoundException(id))

    /**
     * Creates a new promoter.
     *
     * The slug is auto-generated from the promoter name
     * (e.g. `"36 Concerts"` → `"36-concerts"`).
     */
    suspend fun create(request: PromoterRequest): PromoterResponse {
        val slug = SlugGenerator.slugify(request.name)
        // Pre-check for slug uniqueness to provide a clear error message instead of
        // relying on the DB constraint, which would produce a generic 409 response.
        ensureSlugAvailable(request.name, slug)

        // Route through the domain model to ensure any future business rules
        // (e.g. validation, normalization) are consistently applied (see ADR-003).
        val promoter =
            Promoter(
                name = request.name,
                slug = slug,
                websiteUrl = request.websiteUrl,
                imageUrl = request.imageUrl
            )
        val entity = PromoterEntity.fromDomain(promoter)
        val saved = promoterRepository.save(entity)
        logger.info { "Created promoter '${saved.name}' with id ${saved.id}" }
        return PromoterResponse.fromDomain(saved.toDomain())
    }

    /**
     * Replaces all mutable fields of an existing promoter.
     *
     * @throws PromoterNotFoundException if no promoter with the given [id] exists.
     */
    suspend fun update(
        id: Long,
        request: PromoterRequest
    ): PromoterResponse {
        val existing =
            promoterRepository.findById(id)
                ?: throw PromoterNotFoundException(id)

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
                websiteUrl = request.websiteUrl,
                imageUrl = request.imageUrl
            )
        val saved = promoterRepository.save(updated)
        logger.info { "Updated promoter '${saved.name}' (id=${saved.id})" }
        return PromoterResponse.fromDomain(saved.toDomain())
    }

    /**
     * Deletes a promoter by [id].
     *
     * @throws PromoterNotFoundException if no promoter with the given [id] exists.
     */
    suspend fun delete(id: Long) {
        if (!promoterRepository.existsById(id)) throw PromoterNotFoundException(id)
        // Join table rows (event_promoter) are cascade-deleted by the database FK constraint (ON DELETE CASCADE).
        promoterRepository.deleteById(id)
        logger.info { "Deleted promoter with id $id" }
    }

    /**
     * Checks whether the given [slug] is already taken by another promoter and throws
     * [DuplicatePromoterSlugException] if so. This provides a clear, domain-specific error
     * message to the API consumer instead of relying on the DB's generic constraint violation.
     */
    private suspend fun ensureSlugAvailable(
        name: String,
        slug: String
    ) {
        if (promoterRepository.findBySlug(slug) != null) {
            throw DuplicatePromoterSlugException(name, slug)
        }
    }
}

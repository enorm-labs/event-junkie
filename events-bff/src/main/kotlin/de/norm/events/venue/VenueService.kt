package de.norm.events.venue

import de.norm.events.common.PageResponse
import de.norm.events.common.sanitizeSort
import de.norm.events.image.CachedImageGate
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Read service for venues backing the venue list and detail pages.
 */
@Service
class VenueService(
    private val venueRepository: VenueRepository,
    private val cachedImageGate: CachedImageGate
) {
    /**
     * Lists venues with pagination, optionally filtered by a case-insensitive name [query]
     * and/or an exact [district] (Bezirk) slug. The two filters combine independently, so any
     * of the four presence combinations selects the matching repository query.
     */
    @Transactional(readOnly = true)
    suspend fun list(
        query: String?,
        district: String?,
        pageable: Pageable
    ): PageResponse<VenueSummaryResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, DEFAULT_SORT)
        val name = query?.takeIf { it.isNotBlank() }
        val borough = district?.takeIf { it.isNotBlank() }
        val (entities, total) =
            when {
                name == null && borough == null -> {
                    venueRepository.findAllBy(safePageable).toList() to venueRepository.count()
                }

                name == null -> {
                    venueRepository.findByDistrict(borough!!, safePageable).toList() to
                        venueRepository.countByDistrict(borough)
                }

                borough == null -> {
                    venueRepository.findByNameContainingIgnoreCase(name, safePageable).toList() to
                        venueRepository.countByNameContainingIgnoreCase(name)
                }

                else -> {
                    venueRepository.findByNameContainingIgnoreCaseAndDistrict(name, borough, safePageable).toList() to
                        venueRepository.countByNameContainingIgnoreCaseAndDistrict(name, borough)
                }
            }
        val images = cachedImageGate.forUrls(entities.map { it.imageUrl })
        return PageResponse.of(
            entities.map { VenueSummaryResponse.fromEntity(it, images.serve(it.imageUrl, RENDERED_WIDTH)) },
            safePageable,
            total
        )
    }

    /**
     * Finds a single venue by [slug].
     *
     * @throws VenueNotFoundException if no venue with the given slug exists.
     */
    @Transactional(readOnly = true)
    suspend fun findBySlug(slug: String): VenueDetailResponse {
        val entity = venueRepository.findBySlug(slug) ?: throw VenueNotFoundException(slug)
        val image = cachedImageGate.forUrls(listOf(entity.imageUrl)).serve(entity.imageUrl, RENDERED_WIDTH)
        return VenueDetailResponse.fromEntity(entity, image)
    }

    companion object {
        /**
         * What the site draws one of these at, in CSS pixels.
         *
         * `VenueCard` uses 80 and `BaseDetailView`'s header 96, so one number covers both — the
         * gate offers everything from the slot up to three times it either way.
         */
        private const val RENDERED_WIDTH = 96

        /** Entity properties a client may sort the venue list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = setOf("name", "slug", "city")
        private val DEFAULT_SORT = Sort.by("name")
    }
}

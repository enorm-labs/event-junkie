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
     * Lists venues, optionally filtered by a case-insensitive name [query] and/or an exact
     * [district] slug; the two combine independently.
     */
    @Transactional(readOnly = true)
    suspend fun list(
        query: String?,
        district: String?,
        pageable: Pageable
    ): PageResponse<VenueSummaryResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, DEFAULT_SORT)
        val name = query?.takeIf { it.isNotBlank() }
        val districtSlug = district?.takeIf { it.isNotBlank() }
        val (entities, total) =
            when {
                name != null && districtSlug != null -> {
                    venueRepository.findByNameContainingIgnoreCaseAndDistrict(name, districtSlug, safePageable).toList() to
                        venueRepository.countByNameContainingIgnoreCaseAndDistrict(name, districtSlug)
                }

                name != null -> {
                    venueRepository.findByNameContainingIgnoreCase(name, safePageable).toList() to
                        venueRepository.countByNameContainingIgnoreCase(name)
                }

                districtSlug != null -> {
                    venueRepository.findByDistrict(districtSlug, safePageable).toList() to
                        venueRepository.countByDistrict(districtSlug)
                }

                else -> {
                    venueRepository.findAllBy(safePageable).toList() to venueRepository.count()
                }
            }
        val images = cachedImageGate.forUrls(entities.map { it.imageUrl })
        return PageResponse.of(
            entities.map { VenueSummaryResponse.fromEntity(it, images.serve(it.imageUrl, POSTER_WIDTH)) },
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
        val image = cachedImageGate.forUrl(entity.imageUrl, DETAIL_WIDTH)
        return VenueDetailResponse.fromEntity(entity, image)
    }

    companion object {
        /**
         * What the site draws one of these at, in CSS pixels: a venue card at about 474 px in the
         * two-column grid, so 512 and 768; `BaseDetailView` at 704 px, which its `sizes` states. CSS
         * pixels, not file widths: the device pixel ratio is the browser's to apply.
         */
        private const val POSTER_WIDTH = 480
        private const val DETAIL_WIDTH = 704

        /** Entity properties a client may sort the venue list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = setOf("name", "slug", "city")
        private val DEFAULT_SORT = Sort.by("name")
    }
}

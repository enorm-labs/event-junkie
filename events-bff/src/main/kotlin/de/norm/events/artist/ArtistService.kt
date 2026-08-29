package de.norm.events.artist

import de.norm.events.common.PageResponse
import de.norm.events.common.sanitizeSort
import de.norm.events.image.CachedImageGate
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Read service for artists backing the artist list/search and detail pages.
 */
@Service
class ArtistService(
    private val artistRepository: ArtistRepository,
    private val cachedImageGate: CachedImageGate
) {
    /**
     * Lists artists with pagination, optionally filtered by a case-insensitive name [query].
     */
    @Transactional(readOnly = true)
    suspend fun list(
        query: String?,
        pageable: Pageable
    ): PageResponse<ArtistSummaryResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, DEFAULT_SORT)
        val (entities, total) =
            if (query.isNullOrBlank()) {
                artistRepository.findAllBy(safePageable).toList() to artistRepository.count()
            } else {
                artistRepository.findByNameContainingIgnoreCase(query, safePageable).toList() to
                    artistRepository.countByNameContainingIgnoreCase(query)
            }
        val images = cachedImageGate.forUrls(entities.map { it.imageUrl })
        return PageResponse.of(
            entities.map { ArtistSummaryResponse.fromEntity(it, images.serve(it.imageUrl, RENDERED_WIDTH)) },
            safePageable,
            total
        )
    }

    /**
     * Finds a single artist by [slug].
     *
     * @throws ArtistNotFoundException if no artist with the given slug exists.
     */
    @Transactional(readOnly = true)
    suspend fun findBySlug(slug: String): ArtistDetailResponse {
        val entity = artistRepository.findBySlug(slug) ?: throw ArtistNotFoundException(slug)
        val image = cachedImageGate.forUrls(listOf(entity.imageUrl)).serve(entity.imageUrl, RENDERED_WIDTH)
        return ArtistDetailResponse.fromEntity(entity, image)
    }

    companion object {
        /**
         * What the site draws one of these at, in CSS pixels.
         *
         * `BaseDetailView`'s header draws 96, and the list renders no image today. The gate offers
         * everything from the slot up to three times it, so this covers a card if one arrives.
         */
        private const val RENDERED_WIDTH = 96

        /** Entity properties a client may sort the artist list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = setOf("name", "slug")
        private val DEFAULT_SORT = Sort.by("name")
    }
}

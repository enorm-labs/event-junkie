package de.norm.events.artist

import de.norm.events.common.PageResponse
import de.norm.events.common.TextSearch
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
    private val artistSearchRepository: ArtistSearchRepository,
    private val cachedImageGate: CachedImageGate
) {
    /**
     * Lists artists with pagination, optionally filtered by a name [query] ([TextSearch]), by name. Without a
     * sort, a search lists the closest names first (#2704).
     */
    @Transactional(readOnly = true)
    suspend fun list(
        query: String?,
        pageable: Pageable,
        countCap: Int? = null
    ): PageResponse<ArtistSummaryResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, Sort.unsorted())
        val page = artistSearchRepository.search(TextSearch.term(query), safePageable, countCap)
        val byId = artistRepository.findByIdIn(page.ids).toList().associateBy { it.id }
        val entities = page.ids.mapNotNull { byId[it] }
        val images = cachedImageGate.forUrls(entities.map { it.imageUrl })
        return PageResponse.of(
            entities.map { ArtistSummaryResponse.fromEntity(it, images.serve(it.imageUrl, CARD_WIDTH)) },
            safePageable,
            page.total
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
        val image = cachedImageGate.forUrl(entity.imageUrl, DETAIL_WIDTH)
        return ArtistDetailResponse.fromEntity(entity, image)
    }

    companion object {
        /**
         * What the site draws one of these at, in CSS pixels: `BaseDetailView` at 704 px, which its
         * `sizes` states. The list renders no image today, so [CARD_WIDTH] is the thumbnail width
         * `EventService` uses for the same slot. CSS pixels, not file widths.
         */
        private const val CARD_WIDTH = 96
        private const val DETAIL_WIDTH = 704

        /** Entity properties a client may sort the artist list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = ArtistSearchRepository.SORT_COLUMNS.keys
    }
}

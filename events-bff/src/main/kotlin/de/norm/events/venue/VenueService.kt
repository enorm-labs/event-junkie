package de.norm.events.venue

import de.norm.events.common.PageResponse
import de.norm.events.common.sanitizeSort
import de.norm.events.image.CachedImageGate
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * Read service for venues backing the venue list and detail pages.
 */
@Service
class VenueService(
    private val venueRepository: VenueRepository,
    private val venueSearchRepository: VenueSearchRepository,
    private val characterTagRepository: VenueCharacterTagRepository,
    private val cachedImageGate: CachedImageGate,
    private val clock: Clock
) {
    /**
     * Lists the venues that match [filter], by name or by their events in the next 30 days (#360, #2694). Without a
     * sort, a search lists the closest names first.
     */
    @Transactional(readOnly = true)
    suspend fun list(
        filter: VenueFilter,
        pageable: Pageable,
        countCap: Int? = null
    ): PageResponse<VenueListItemResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, Sort.unsorted())
        val page = venueSearchRepository.search(filter, LocalDateTime.now(clock), safePageable, countCap)
        val entities = venueRepository.findByIdIn(page.rows.map { it.id }).toList().associateBy { it.id }
        val images = cachedImageGate.forUrls(entities.values.map { it.imageUrl })
        val tags = characterTagRepository.findByVenueIds(entities.keys.filterNotNull())
        return PageResponse.of(
            page.rows.mapNotNull { row ->
                entities[row.id]?.let {
                    VenueListItemResponse.fromEntity(
                        it,
                        images.serve(it.imageUrl, POSTER_WIDTH),
                        row,
                        tags[row.id].orEmpty().map { tag -> tag.tag }
                    )
                }
            },
            safePageable,
            page.total
        )
    }

    /** For each character tag, how many venues match [filter] with that tag added (#2671). */
    @Transactional(readOnly = true)
    suspend fun featureCounts(filter: VenueFilter): Map<String, Long> = venueSearchRepository.featureCounts(filter)

    /**
     * Finds a single venue by [slug].
     *
     * @throws VenueNotFoundException if no venue with the given slug exists.
     */
    @Transactional(readOnly = true)
    suspend fun findBySlug(slug: String): VenueDetailResponse {
        val entity = venueRepository.findBySlug(slug) ?: throw VenueNotFoundException(slug)
        val image = cachedImageGate.forUrl(entity.imageUrl, DETAIL_WIDTH)
        val tags = entity.id?.let { characterTagRepository.findByVenueIds(listOf(it))[it] }.orEmpty()
        return VenueDetailResponse.fromEntity(entity, image, tags.map { VenueCharacterTagResponse(it.tag, it.sourceUrl) })
    }

    companion object {
        /**
         * What the site draws one of these at, in CSS pixels: a venue card at about 474 px in the
         * two-column grid, so 512 and 768; `BaseDetailView` at 704 px, which its `sizes` states. CSS
         * pixels, not file widths: the device pixel ratio is the browser's to apply.
         */
        private const val POSTER_WIDTH = 480
        private const val DETAIL_WIDTH = 704

        /** Properties a client may sort the venue list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = VenueSearchRepository.SORT_COLUMNS.keys
    }
}

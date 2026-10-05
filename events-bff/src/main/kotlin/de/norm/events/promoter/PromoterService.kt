package de.norm.events.promoter

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
 * Read service for promoters backing the promoter list and detail pages.
 */
@Service
class PromoterService(
    private val promoterRepository: PromoterRepository,
    private val promoterSearchRepository: PromoterSearchRepository,
    private val cachedImageGate: CachedImageGate,
    private val clock: Clock
) {
    /**
     * Lists promoters with pagination, optionally filtered by a name [query], by name or by their
     * events in the next 30 days (#1349, #2694). Without a sort, a search lists the closest names first.
     */
    @Transactional(readOnly = true)
    suspend fun list(
        query: String?,
        pageable: Pageable,
        countCap: Int? = null
    ): PageResponse<PromoterListItemResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, Sort.unsorted())
        val page = promoterSearchRepository.search(query, LocalDateTime.now(clock), safePageable, countCap)
        val entities = promoterRepository.findByIdIn(page.rows.map { it.id }).toList().associateBy { it.id }
        return PageResponse.of(
            page.rows.mapNotNull { row -> entities[row.id]?.let { PromoterListItemResponse.fromEntity(it, row) } },
            safePageable,
            page.total
        )
    }

    /**
     * Finds a single promoter by [slug].
     *
     * @throws PromoterNotFoundException if no promoter with the given slug exists.
     */
    @Transactional(readOnly = true)
    suspend fun findBySlug(slug: String): PromoterDetailResponse {
        val entity = promoterRepository.findBySlug(slug) ?: throw PromoterNotFoundException(slug)
        val image = cachedImageGate.forUrl(entity.imageUrl, DETAIL_WIDTH)
        return PromoterDetailResponse.fromEntity(entity, image)
    }

    companion object {
        /**
         * What the site draws the detail picture at, in CSS pixels: `BaseDetailView` at 704 px, which
         * its `sizes` states. The device pixel ratio is the browser's to apply.
         */
        private const val DETAIL_WIDTH = 704

        /** Properties a client may sort the promoter list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = PromoterSearchRepository.SORT_COLUMNS.keys
    }
}

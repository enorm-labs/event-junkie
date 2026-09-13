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
import java.time.LocalDate

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
     * Lists promoters with pagination, optionally filtered by a case-insensitive name [query],
     * sorted by name or by how many events each still has to come (#1349).
     */
    @Transactional(readOnly = true)
    suspend fun list(
        query: String?,
        pageable: Pageable
    ): PageResponse<PromoterListItemResponse> {
        val safePageable = pageable.sanitizeSort(SORTABLE_PROPERTIES, DEFAULT_SORT)
        val page = promoterSearchRepository.search(query, LocalDate.now(clock), safePageable)
        val entities = promoterRepository.findByIdIn(page.rows.map { it.id }).toList().associateBy { it.id }
        return PageResponse.of(
            page.rows.mapNotNull { row -> entities[row.id]?.let { PromoterListItemResponse.fromEntity(it, row.upcomingEventCount) } },
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
        val image = cachedImageGate.forUrls(listOf(entity.imageUrl)).serve(entity.imageUrl, DETAIL_WIDTH)
        return PromoterDetailResponse.fromEntity(entity, image)
    }

    companion object {
        /**
         * What the site draws the detail picture at, in CSS pixels: `BaseDetailView` leads with it
         * at the full width of a `max-w-3xl` column, 704 px after padding, and its `sizes`
         * attribute states the same number. The device pixel ratio is the browser's to know, and
         * it picks from the `srcset` this produces.
         */
        private const val DETAIL_WIDTH = 704

        /** Properties a client may sort the promoter list by; anything else is ignored. */
        private val SORTABLE_PROPERTIES = PromoterSearchRepository.SORT_COLUMNS.keys
        private val DEFAULT_SORT = Sort.by("name")
    }
}

package de.norm.events.genretag

import de.norm.events.common.PageResponse
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for listing available genre tags.
 *
 * Genre tags are auto-created during event imports by the scraper pipeline —
 * there is no manual CRUD API for creating tags. This service provides
 * read-only access for the frontend to populate filter dropdowns.
 */
@Service
class GenreTagService(
    private val genreTagRepository: GenreTagRepository
) {
    /**
     * Lists all genre tags with pagination and sorting.
     *
     * Intended for frontend filter dropdowns. Default sort is by `name`.
     */
    @Transactional(readOnly = true)
    suspend fun findAll(pageable: Pageable): PageResponse<GenreTagResponse> =
        PageResponse.of(
            genreTagRepository.findAllBy(pageable).toList().map { GenreTagResponse.fromDomain(it.toDomain()) },
            pageable,
            genreTagRepository.count()
        )

    /**
     * Finds a single genre tag by its database [id].
     *
     * @throws GenreTagNotFoundException if no genre tag with the given [id] exists.
     */
    @Transactional(readOnly = true)
    suspend fun findById(id: Long): GenreTagResponse {
        val entity = genreTagRepository.findById(id) ?: throw GenreTagNotFoundException(id)
        return GenreTagResponse.fromDomain(entity.toDomain())
    }
}

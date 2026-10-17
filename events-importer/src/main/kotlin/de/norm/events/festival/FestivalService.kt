package de.norm.events.festival

import de.norm.events.common.PageResponse
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/** Festival CRUD for the admin API. The slug is always `SlugGenerator.slugify(name)`. */
@Service
class FestivalService(
    private val festivalRepository: FestivalRepository
) {
    private val logger = KotlinLogging.logger {}

    suspend fun findAll(pageable: Pageable): PageResponse<FestivalResponse> =
        PageResponse.of(
            festivalRepository.findAllBy(pageable).map { FestivalResponse.fromEntity(it) }.toList(),
            pageable,
            festivalRepository.count()
        )

    suspend fun findById(id: Long): FestivalResponse = FestivalResponse.fromEntity(festivalRepository.findById(id) ?: throw FestivalNotFoundException(id))

    suspend fun create(request: FestivalRequest): FestivalResponse {
        val slug = SlugGenerator.slugify(request.name)
        ensureSlugAvailable(request.name, slug)
        val saved = festivalRepository.save(request.applyTo(base = null, slug = slug))
        logger.info { "Created festival '${saved.name}' with id ${saved.id}" }
        return FestivalResponse.fromEntity(saved)
    }

    /** Replaces every field of festival [id]. A rename changes the slug. */
    suspend fun update(
        id: Long,
        request: FestivalRequest
    ): FestivalResponse {
        val existing = festivalRepository.findById(id) ?: throw FestivalNotFoundException(id)
        val slug = SlugGenerator.slugify(request.name)
        if (slug != existing.slug) ensureSlugAvailable(request.name, slug)
        val saved = festivalRepository.save(request.applyTo(base = existing, slug = slug))
        logger.info { "Updated festival '${saved.name}' (id=${saved.id})" }
        return FestivalResponse.fromEntity(saved)
    }

    suspend fun delete(id: Long) {
        if (!festivalRepository.existsById(id)) throw FestivalNotFoundException(id)
        festivalRepository.deleteById(id)
        logger.info { "Deleted festival with id $id" }
    }

    // A clear 409 before the save, rather than the unique constraint's generic one.
    private suspend fun ensureSlugAvailable(
        name: String,
        slug: String
    ) {
        if (festivalRepository.findBySlug(slug) != null) throw DuplicateFestivalSlugException(name, slug)
    }
}

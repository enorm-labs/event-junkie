package de.norm.events.scraper

import de.norm.events.common.PageResponse
import de.norm.events.event.EventRepository
import de.norm.events.licence.SourceLicences
import de.norm.events.slug.SlugGenerator
import de.norm.events.venue.VenueNotFoundException
import de.norm.events.venue.VenueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Event source CRUD. Slugs are always generated from the name by [SlugGenerator].
 */
@Service
class EventSourceService(
    private val eventSourceRepository: EventSourceRepository,
    private val venueRepository: VenueRepository,
    private val eventRepository: EventRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists event sources, paged and sorted by [pageable].
     */
    @Transactional(readOnly = true)
    suspend fun findAll(pageable: Pageable): PageResponse<EventSourceResponse> =
        PageResponse.of(
            eventSourceRepository.findAllBy(pageable).map { EventSourceResponse.fromEntity(it) }.toList(),
            pageable,
            eventSourceRepository.count()
        )

    /**
     * Finds a single event source by [slug].
     *
     * @throws EventSourceNotFoundException if no source with the given [slug] exists.
     */
    @Transactional(readOnly = true)
    suspend fun findBySlug(slug: String): EventSourceResponse {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        return EventSourceResponse.fromEntity(source)
    }

    /**
     * Creates a new event source; the slug is generated from the name. `import` and `retry` are
     * reserved for API path segments.
     *
     * @throws VenueNotFoundException if the referenced venue does not exist.
     * @throws InvalidSourceTypeException if the source type is not a valid [EventSource] value.
     * @throws ReservedSlugException if the generated slug conflicts with a reserved API path.
     */
    suspend fun create(request: EventSourceCreateRequest): EventSourceResponse {
        validateCreateRequest(request)

        val slug = SlugGenerator.slugify(request.name)
        if (slug in RESERVED_SLUGS) throw ReservedSlugException(slug)

        val entity =
            EventSourceEntity(
                venueId = request.venueId,
                name = request.name,
                slug = slug,
                url = request.url,
                sourceType = request.sourceType,
                enabled = request.enabled,
                importIntervalMinutes = request.importIntervalMinutes,
                maxRetries = request.maxRetries
            )
        val saved = eventSourceRepository.save(entity)
        logger.info { "Created event source '${saved.name}' with id ${saved.id}" }
        return EventSourceResponse.fromEntity(saved)
    }

    /**
     * Validates the create request: `sourceType` is an [EventSource] value, and the venue exists,
     * so the client gets a clear error rather than a failure at import time or a 409 from the FK.
     */
    private suspend fun validateCreateRequest(request: EventSourceCreateRequest) {
        try {
            EventSource.valueOf(request.sourceType)
        } catch (_: IllegalArgumentException) {
            throw InvalidSourceTypeException(request.sourceType)
        }

        if (!venueRepository.existsById(request.venueId)) throw VenueNotFoundException(request.venueId)
    }

    /**
     * Partially updates a source; only non-null fields apply, the licence columns included, so a
     * status can be corrected but not cleared back to unreviewed: reverting a review to "nobody
     * looked" discards the fact that somebody did, and the honest correction is `UNCLEAR` (#283).
     *
     * @throws EventSourceNotFoundException if no source with the given [slug] exists.
     */
    suspend fun update(
        slug: String,
        request: EventSourceUpdateRequest
    ): EventSourceResponse {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        // A licence field in the request means somebody reviewed the source, so the timestamp moves
        // with it rather than being sent separately, where the two could disagree.
        val licenceReviewed =
            request.descriptionLicence != null || request.imageLicence != null || request.translationLicence != null
        val updated =
            source.copy(
                enabled = request.enabled ?: source.enabled,
                importIntervalMinutes = request.importIntervalMinutes ?: source.importIntervalMinutes,
                maxRetries = request.maxRetries ?: source.maxRetries,
                descriptionLicence = request.descriptionLicence?.name ?: source.descriptionLicence,
                imageLicence = request.imageLicence?.name ?: source.imageLicence,
                translationLicence = request.translationLicence?.name ?: source.translationLicence,
                licenceReviewedAt = if (licenceReviewed) Instant.now() else source.licenceReviewedAt,
                licenceSourceUrl = request.licenceSourceUrl ?: source.licenceSourceUrl,
                licenceNote = request.licenceNote ?: source.licenceNote
            )
        // Do not claim an update that did not happen (#814): every field is nullable, so an empty body
        // copies the row to itself, and the log has to tell that from an update.
        if (updated == source) {
            logger.info { "No change for event source '${source.name}' (id=${source.id}): the request set nothing" }
            return EventSourceResponse.fromEntity(source)
        }
        val saved = eventSourceRepository.save(updated)
        clearProhibitedContent(saved)
        logger.info { "Updated event source '${saved.name}' (id=${saved.id})" }
        return EventSourceResponse.fromEntity(saved)
    }

    /**
     * Deletes stored content the source now forbids. Withholding and storing are different acts,
     * and `PROHIBITED` answers both (#807): the gate stops the § 19a UrhG communication, this stops
     * the § 16 reproduction the moment the prohibition is recorded, since a past event is never
     * scraped again. Runs on every update rather than only on a transition: re-clearing matches no
     * rows, and a rule that fires only on a change is one stale read away from missing its case.
     */
    private suspend fun clearProhibitedContent(source: EventSourceEntity) {
        val id = source.id ?: return
        val licences = SourceLicences.of(source.descriptionLicence, source.imageLicence, source.translationLicence)
        if (licences.withholdsDescription()) {
            val cleared = eventRepository.clearDescriptions(id)
            if (cleared > 0) logger.info { "Cleared $cleared stored description(s) for prohibited source '${source.slug}'" }
        }
        if (licences.withholdsImage()) {
            val cleared = eventRepository.clearImageUrls(id)
            if (cleared > 0) logger.info { "Cleared $cleared stored image URL(s) for prohibited source '${source.slug}'" }
        }
        // A translation exists only while a grant does; withdrawing it deletes the derived text now.
        if (!licences.allowsTranslation()) {
            val cleared = eventRepository.clearTranslations(id)
            if (cleared > 0) logger.info { "Cleared $cleared stored translation(s) for source '${source.slug}'" }
        }
    }

    /**
     * Resets a failed or misconfigured source for immediate retry: error state cleared,
     * `retryCount` 0, status IDLE, `lastImportAt` preserved ([ScheduledImportService.isDue] treats
     * IDLE as always-due). A MISCONFIGURED source's configuration must be fixed first.
     *
     * @throws EventSourceNotFoundException if no source with the given [slug] exists.
     */
    suspend fun retry(slug: String): EventSourceResponse {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        logger.info { "Resetting source '${source.name}' for retry (previous import: ${source.lastImportAt})" }
        val reset =
            source.copy(
                status = ImportStatus.IDLE.name,
                retryCount = 0,
                lastError = null
            )
        val saved = eventSourceRepository.save(reset)
        logger.info { "Reset event source '${saved.name}' (id=${saved.id}) for retry" }
        return EventSourceResponse.fromEntity(saved)
    }

    /**
     * Resets all failed and misconfigured sources to IDLE in one bulk UPDATE.
     *
     * @return the number of sources reset.
     */
    suspend fun retryAll(): Int {
        val count = eventSourceRepository.resetAllFailedToIdle()
        logger.info { "Bulk-reset $count failed event source(s) for retry" }
        return count
    }

    /**
     * Deletes an event source by [slug].
     *
     * @throws EventSourceNotFoundException if no source with the given [slug] exists.
     */
    suspend fun delete(slug: String) {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        eventSourceRepository.delete(source)
        logger.info { "Deleted event source '${source.name}' (id=${source.id})" }
    }

    companion object {
        /**
         * Slugs reserved for API path segments: `POST /import` and `POST /retry` in
         * [EventSourceController]. Extend when a sub-resource path is added.
         */
        private val RESERVED_SLUGS = setOf("import", "retry")
    }
}

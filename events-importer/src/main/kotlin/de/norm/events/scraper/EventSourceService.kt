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
 * Service encapsulating event source CRUD business logic.
 *
 * All methods are suspending to align with the reactive R2DBC stack.
 * Slugs are always auto-generated from the source name using [SlugGenerator].
 */
@Service
class EventSourceService(
    private val eventSourceRepository: EventSourceRepository,
    private val venueRepository: VenueRepository,
    private val eventRepository: EventRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists event sources with pagination and sorting.
     *
     * Pagination and sorting are controlled by the [pageable] parameter, which Spring
     * resolves from `page`, `size`, and `sort` query parameters
     * (e.g. `?page=0&size=20&sort=name,asc`).
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
     * Creates a new event source.
     *
     * The slug is auto-generated from the source name
     * (e.g. `"Cassiopeia Website"` → `"cassiopeia-website"`).
     *
     * Certain slugs are reserved for API path segments (`import`, `retry`)
     * to prevent routing conflicts.
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
     * Validates the create request before persisting.
     *
     * - Ensures the `sourceType` is a valid [EventSource] enum value.
     * - Ensures the referenced venue exists (avoids a misleading 409 from the FK constraint).
     */
    private suspend fun validateCreateRequest(request: EventSourceCreateRequest) {
        // Validate sourceType against the EventSource enum early to provide a clear
        // error message instead of failing at import time with a cryptic error.
        try {
            EventSource.valueOf(request.sourceType)
        } catch (_: IllegalArgumentException) {
            throw InvalidSourceTypeException(request.sourceType)
        }

        // Validate that the referenced venue exists before persisting, so the client
        // gets a clear 404 instead of a misleading 409 from the FK constraint violation.
        if (!venueRepository.existsById(request.venueId)) throw VenueNotFoundException(request.venueId)
    }

    /**
     * Partially updates an event source's configuration.
     *
     * Supports toggling `enabled`, changing `importIntervalMinutes`, and adjusting `maxRetries`.
     * Only non-null fields in the request are applied.
     *
     * **A null field means "leave unchanged", which is this route's existing semantic and applies to
     * the licence columns too.** So a status can be corrected but not cleared back to unreviewed.
     * That is deliberate rather than an oversight: reverting a review to "nobody looked" discards
     * the fact that somebody did, and the honest correction is `UNCLEAR` with a note (#283).
     *
     * @throws EventSourceNotFoundException if no source with the given [slug] exists.
     */
    suspend fun update(
        slug: String,
        request: EventSourceUpdateRequest
    ): EventSourceResponse {
        val source = eventSourceRepository.findBySlug(slug) ?: throw EventSourceNotFoundException(slug)
        // A licence field in the request means somebody reviewed the source, so the timestamp moves
        // with it rather than being sent by the caller. Sending it separately would let the two
        // disagree, and the whole value of the timestamp is that it cannot.
        val licenceReviewed = request.descriptionLicence != null || request.imageLicence != null
        val updated =
            source.copy(
                enabled = request.enabled ?: source.enabled,
                importIntervalMinutes = request.importIntervalMinutes ?: source.importIntervalMinutes,
                maxRetries = request.maxRetries ?: source.maxRetries,
                descriptionLicence = request.descriptionLicence?.name ?: source.descriptionLicence,
                imageLicence = request.imageLicence?.name ?: source.imageLicence,
                licenceReviewedAt = if (licenceReviewed) Instant.now() else source.licenceReviewedAt,
                licenceSourceUrl = request.licenceSourceUrl ?: source.licenceSourceUrl,
                licenceNote = request.licenceNote ?: source.licenceNote
            )
        // **Do not claim an update that did not happen (#814).** Every field of the request is
        // nullable, so a body carrying nothing this endpoint recognises copies the row to itself and
        // used to log an update all the same — leaving the server's own record of a failed apply
        // saying it succeeded. Unknown fields are now a 400, but an explicitly empty body still
        // reaches here and is still a no-op, so the log has to tell the two apart.
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
     * Deletes stored content the source now forbids.
     *
     * **Withholding a field and storing it are different acts, and `PROHIBITED` answers both (#807).**
     * The gate stops the § 19a UrhG communication to the public. This stops the § 16 UrhG
     * reproduction, at the moment the prohibition is recorded — which is what makes the promise on
     * `ForVenuesView` true. Waiting for the next import would leave every past event untouched
     * forever, because a past event is never scraped again.
     *
     * Runs on every update rather than only on a transition. Re-clearing an already-cleared source
     * matches no rows, and a rule that fires only on a change is one stale read away from missing
     * the case it exists for.
     */
    private suspend fun clearProhibitedContent(source: EventSourceEntity) {
        val id = source.id ?: return
        val licences = SourceLicences.of(source.descriptionLicence, source.imageLicence)
        if (licences.withholdsDescription()) {
            val cleared = eventRepository.clearDescriptions(id)
            if (cleared > 0) logger.info { "Cleared $cleared stored description(s) for prohibited source '${source.slug}'" }
        }
        if (licences.withholdsImage()) {
            val cleared = eventRepository.clearImageUrls(id)
            if (cleared > 0) logger.info { "Cleared $cleared stored image URL(s) for prohibited source '${source.slug}'" }
        }
    }

    /**
     * Resets a failed or misconfigured event source for immediate retry.
     *
     * Clears the error state, resets `retryCount` to 0, and sets status back to IDLE
     * so the scheduler will pick it up on the next tick. The `lastImportAt` timestamp
     * is preserved as a historical record — [ScheduledImportService.isDue] treats IDLE
     * sources as always-due regardless of when they last ran.
     *
     * For MISCONFIGURED sources, the underlying configuration issue (e.g. unknown source type,
     * missing importer) must be fixed before retrying — otherwise the source will immediately
     * be marked as MISCONFIGURED again.
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
     * Resets all failed and misconfigured event sources back to IDLE for immediate retry.
     *
     * Useful after fixing a network issue, scraper bug, or configuration problem — all
     * failed and misconfigured sources are picked up by the scheduler on the next tick.
     *
     * Uses a single bulk UPDATE statement instead of fetching and saving each
     * source individually for better performance.
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
         * Slugs reserved for API path segments to prevent routing conflicts.
         *
         * These correspond to sub-resource paths in [EventSourceController]:
         * `POST /import` and `POST /retry`. Update this set if new
         * sub-resource paths are added to the controller.
         */
        private val RESERVED_SLUGS = setOf("import", "retry")
    }
}

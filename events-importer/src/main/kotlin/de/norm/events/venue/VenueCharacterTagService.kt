package de.norm.events.venue

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.server.ServerWebInputException

/**
 * Sets and removes a venue's character tags (#2379). Each tag carries the URL of the venue's own
 * page that states it, so the request without one is refused by validation before it gets here.
 */
@Service
class VenueCharacterTagService(
    private val venueRepository: VenueRepository,
    private val store: VenueCharacterTagStore
) {
    private val logger = KotlinLogging.logger {}

    /** The venue's tags in vocabulary order. */
    suspend fun list(venueId: Long): List<VenueCharacterTagResponse> {
        ensureVenueExists(venueId)
        return store.findByVenueId(venueId).toResponses()
    }

    /** Sets [tagSlug] on the venue with [sourceUrl], replacing the URL of a tag already set. */
    suspend fun set(
        venueId: Long,
        tagSlug: String,
        sourceUrl: String
    ): VenueCharacterTagResponse {
        val tag = resolve(tagSlug)
        ensureVenueExists(venueId)
        val row = store.upsert(venueId, tag.slug, sourceUrl)
        logger.info { "Set character tag '${tag.slug}' on venue $venueId from $sourceUrl" }
        return VenueCharacterTagResponse(tag.slug, row.sourceUrl)
    }

    /** Removes [tagSlug] from the venue. Removing a tag that is not set is not an error. */
    suspend fun remove(
        venueId: Long,
        tagSlug: String
    ) {
        val tag = resolve(tagSlug)
        ensureVenueExists(venueId)
        if (store.delete(venueId, tag.slug)) {
            logger.info { "Removed character tag '${tag.slug}' from venue $venueId" }
        }
    }

    private suspend fun ensureVenueExists(venueId: Long) {
        if (!venueRepository.existsById(venueId)) throw VenueNotFoundException(venueId)
    }

    // A ServerWebInputException, so GlobalExceptionHandler answers 400 and names the vocabulary.
    private fun resolve(slug: String): VenueCharacterTag =
        VenueCharacterTag.fromSlug(slug)
            ?: throw ServerWebInputException(
                "Unknown character tag '$slug'. Accepted: ${VenueCharacterTag.entries.joinToString(", ") { it.slug }}."
            )

    private fun List<VenueCharacterTagRow>.toResponses(): List<VenueCharacterTagResponse> =
        sortedBy { VenueCharacterTag.fromSlug(it.tag)?.ordinal ?: Int.MAX_VALUE }
            .map { VenueCharacterTagResponse(it.tag, it.sourceUrl) }
}

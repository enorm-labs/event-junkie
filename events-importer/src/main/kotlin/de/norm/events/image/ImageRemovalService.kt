package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Removes cached images: on an operator's request, and on a schedule.
 *
 * **The takedown is what makes the venue opt-out true.** `SCRAPING_POSITION.md` §5 promises a venue
 * that its data comes down, and `ForVenuesView` publishes that promise. Hotlinking honoured it by
 * itself, because we held no copy; a bucket does not (ADR-019).
 *
 * **The sweep is what keeps the bucket from growing forever.** The other two buckets carry a
 * lifecycle rule and this one must not, because the objects are live content. Nothing deletes an
 * object when the row that named it goes, so a separate pass has to find them.
 */
@Service
class ImageRemovalService(
    private val repository: CachedImageRepository,
    private val variantRepository: CachedImageVariantRepository,
    private val storage: ImageStorage,
    private val properties: ImageSweepProperties,
    private val metrics: ImageCacheMetrics,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Takes down every image the events of [venueSlug] point at.
     *
     * **It deletes whatever `app.images.sweep.enabled` says**, unlike [sweep]. That switch exists to
     * watch a scheduled rule before trusting it, and an operator asking for their images to go is
     * not a rule being watched.
     *
     * The row is tombstoned before the objects go. The other order leaves a live row claiming
     * objects that no longer exist, which the site serves as a broken image; this order leaves
     * objects that [sweep] collects.
     */
    suspend fun takeDown(venueSlug: String): RemovalOutcome {
        val images = repository.findByVenueSlug(venueSlug).toList()
        val now = clock.instant()
        var objects = 0

        images.forEach { image ->
            repository.save(image.copy(deletedAt = now, updatedAt = now))
            objects += discard(image, delete = true)
        }

        logger.info { "Takedown for venue '$venueSlug': ${images.size} image(s), $objects object(s)" }
        return RemovalOutcome(images = images.size, objects = objects)
    }

    /**
     * One sweep: rows nothing points at any more, then objects no row claims.
     *
     * Both halves are needed. Stale-event cleanup and the `PROHIBITED` licence route leave rows
     * behind that still claim their objects, so without the first half the second finds no orphans
     * and storage still grows.
     */
    suspend fun sweep(): RemovalOutcome {
        if (!storage.isEnabled()) return RemovalOutcome()

        val unreferenced = repository.findUnreferenced(properties.maxDeletes).toList()
        var objects = 0
        unreferenced.forEach { image ->
            objects += discard(image, delete = properties.enabled)
            if (properties.enabled) repository.delete(image)
        }

        val outcome = RemovalOutcome(images = unreferenced.size, objects = objects, strays = sweepBucket())
        metrics.recordSweep(outcome, deleting = properties.enabled)
        if (outcome.total > 0) logger.info { "${if (properties.enabled) "Sweep" else "Sweep (reporting only)"}: $outcome" }
        return outcome
    }

    /**
     * Deletes the objects [image] owns, and the variant rows that named them.
     *
     * @param delete false counts what would go and touches nothing, which is the reporting mode
     *   [ImageSweepProperties.enabled] describes.
     * @return how many objects went, or would have gone.
     */
    @Suppress("ReturnCount")
    private suspend fun discard(
        image: CachedImageEntity,
        delete: Boolean
    ): Int {
        // An image with no hash or no id owns no object, which is not an error.
        val hash = image.contentHash ?: return 0
        val imageId = image.id ?: return 0

        // Keys are the hash of the bytes, so two venues publishing one file share one object.
        // Deleting it for the first would blank the second's card and leave its row claiming it.
        if (repository.countOtherLiveWithHash(hash, imageId) > 0) return 0

        val variants = variantRepository.findByCachedImageId(imageId).toList()
        val keys = variants.map { it.storageKey } + storage.originalKey(hash)
        if (!delete) return keys.size

        val deleted = storage.delete(keys)
        variantRepository.deleteAll(variants)
        return deleted
    }

    /**
     * Objects under this environment's prefix that no live row claims.
     *
     * The listing is the only place a truly lost object can be seen. A `putObject` that succeeded
     * while the row that would have named it failed to save leaves one, and nothing in the database
     * remembers it.
     */
    private suspend fun sweepBucket(): Int {
        val stored = storage.listAll()
        val live = repository.findLiveContentHashes().toList().toSet()

        // A full bucket and no hashes at all is a database that did not answer, not a cache that is
        // empty. Believing it would delete every image we hold.
        if (stored.isNotEmpty() && live.isEmpty()) {
            logger.error { "Sweep refused: ${stored.size} object(s) stored and no cached_image row claims any" }
            return 0
        }

        val cutoff = clock.instant().minus(properties.gracePeriod)
        val strays =
            stored
                .asSequence()
                .filter { it.lastModified.isBefore(cutoff) }
                .filter { storage.contentHashOf(it.key)?.let { hash -> hash !in live } == true }
                .map { it.key }
                .take(properties.maxDeletes)
                .toList()

        if (strays.isNotEmpty()) logger.info { "Sweep found ${strays.size} object(s) no row claims" }
        return if (properties.enabled) storage.delete(strays) else strays.size
    }
}

/** What one removal did, split so a report reads the same as a deletion. */
data class RemovalOutcome(
    val images: Int = 0,
    val objects: Int = 0,
    val strays: Int = 0
) {
    val total: Int get() = images + objects + strays

    override fun toString(): String = "$images image(s), $objects object(s), $strays stray object(s)"
}

package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Keeps `cached_image` in step with the image URLs the importer has seen.
 *
 * **It records what an image is. It stores no bytes** — the storage client arrives in PR 4, and the
 * content hash computed here is what makes two events sharing a poster converge on one object then.
 *
 * Every outcome writes a row, including a refusal. A URL with no row is one nobody has tried; a URL
 * with `failed_at` is one that has been tried and did not work, and the difference is what stops a
 * dead link being requested every night (ADR-019 §3.6).
 */
@Service
class ImageCacheService(
    private val repository: CachedImageRepository,
    private val fetcher: ImageFetcher,
    private val storage: ImageStorage,
    private val properties: ImageProperties,
    private val metrics: ImageCacheMetrics,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Fetches one batch: URLs never tried, then rows old enough to re-check.
     *
     * New URLs come first because an event with no image looks broken, while a stale copy of one
     * still shows something.
     *
     * @return what happened, for the log and for the tests.
     */
    suspend fun refreshBatch(): CacheOutcome {
        if (!properties.fetchEnabled) {
            logger.debug { "Image fetching is disabled; nothing to do" }
            return CacheOutcome()
        }

        val now = clock.instant()
        val fresh = repository.findUncachedImageUrls(properties.batchSize).toList()
        val stale =
            if (fresh.size >= properties.batchSize) {
                emptyList()
            } else {
                repository
                    .findDueForRefresh(
                        refreshBefore = now.minus(properties.refreshAfter),
                        retryBefore = now.minus(properties.retryFailedAfter),
                        limit = properties.batchSize - fresh.size
                    ).toList()
            }

        var outcome = CacheOutcome()
        fresh.forEach { outcome = outcome + record(existing = null, url = it, now = now) }
        stale.forEach { outcome = outcome + record(existing = it, url = it.sourceUrl, now = now) }

        metrics.recordFetchPass(outcome)
        if (outcome.total > 0) logger.info { "Image cache pass: $outcome" }
        return outcome
    }

    private suspend fun record(
        existing: CachedImageEntity?,
        url: String,
        now: Instant
    ): CacheOutcome {
        val base = existing ?: CachedImageEntity(sourceUrl = url)

        return when (val result = fetcher.fetch(url, etag = existing?.etag, lastModified = existing?.lastModified)) {
            is ImageFetchResult.NotModified -> {
                repository.save(base.copy(lastSeenAt = now, updatedAt = now))
                CacheOutcome(unchanged = 1)
            }

            is ImageFetchResult.Rejected -> {
                // The reason is stored so an operator can see *why* an image is missing. Without it
                // a blank card is indistinguishable from a venue that published no image at all.
                repository.save(base.copy(failedAt = now, failureReason = result.reason, lastSeenAt = now, updatedAt = now))
                CacheOutcome(failed = 1)
            }

            is ImageFetchResult.Success -> {
                // Store first, record second. The other order would leave a row claiming an object
                // that does not exist, and the serving path reads the row rather than the bucket.
                val stored = storage.storeOriginal(result.contentHash, result.contentType, result.bytes)

                // **A storage failure is not a bad URL, and must not be cached as one.** The venue
                // answered correctly; our bucket did not take the bytes. Writing a row here would
                // hide the URL behind a refresh window, so a transient outage would cost every image
                // a month. Leaving no row means the next pass finds it again immediately.
                if (storage.isEnabled() && stored == null) return CacheOutcome(failed = 1)

                repository.save(
                    base.copy(
                        contentHash = result.contentHash,
                        contentType = result.contentType,
                        byteSize = result.byteSize,
                        intrinsicWidth = result.width,
                        intrinsicHeight = result.height,
                        etag = result.etag,
                        lastModified = result.lastModified,
                        fetchedAt = now,
                        lastSeenAt = now,
                        // Clearing these is what lets a URL recover. A venue that fixes a broken
                        // image would otherwise stay in the negative cache until somebody noticed.
                        failedAt = null,
                        failureReason = null,
                        updatedAt = now
                    )
                )
                CacheOutcome(fetched = 1)
            }
        }
    }
}

/** What one pass did, split the way `UpsertOutcome` splits an import. */
data class CacheOutcome(
    val fetched: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0
) {
    val total: Int get() = fetched + unchanged + failed

    operator fun plus(other: CacheOutcome): CacheOutcome = CacheOutcome(fetched + other.fetched, unchanged + other.unchanged, failed + other.failed)

    override fun toString(): String = "$fetched fetched, $unchanged unchanged, $failed failed"
}

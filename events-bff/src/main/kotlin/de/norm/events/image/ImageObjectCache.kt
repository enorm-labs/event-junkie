package de.norm.events.image

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Weigher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.stereotype.Component

/**
 * Keeps recently served image bytes in memory, so the bucket sees one request per object.
 * Hetzner Object Storage is Ceph on hard disks, so a miss is a seek, and `Cache-Control:
 * immutable` protects nobody on the first visit (#847). Bounded by bytes rather than entries,
 * because a 192 pixel card is tens of kilobytes and a 1536 pixel detail image hundreds. No
 * expiry: a key names a SHA-256 of the bytes.
 */
@Component
class ImageObjectCache(
    properties: ImageServingProperties,
    registry: MeterRegistry
) {
    private val cache =
        CaffeineCacheMetrics.monitor(
            registry,
            Caffeine
                .newBuilder()
                .maximumWeight(properties.cache.size.toBytes())
                .weigher(Weigher<String, ByteArray> { _, bytes -> bytes.size })
                // Required for the meters to report anything. Caffeine counts nothing without it.
                .recordStats()
                .build<String, ByteArray>(),
            NAME
        )

    /**
     * Returns [storageKey]'s bytes, calling [load] only when this process does not hold them. Only
     * [ImageObject.Found] is kept: caching a missing object or an unreachable store would outlast
     * the fault. Two concurrent misses both call [load], which avoids holding a lock across a
     * network call.
     */
    suspend fun get(
        storageKey: String,
        load: suspend (String) -> ImageObject
    ): ImageObject {
        cache.getIfPresent(storageKey)?.let { return ImageObject.Found(it) }
        return load(storageKey).also { if (it is ImageObject.Found) cache.put(storageKey, it.bytes) }
    }

    /**
     * Bytes currently held, the unit [ImageServingProperties.Cache.size] is set in. `cache_size`
     * from `CaffeineCacheMetrics` is the entry count, which for a weight-bounded cache answers a
     * different question; [ImageServingMetrics] publishes this as a gauge. In-memory work, so safe
     * at scrape time. `cleanUp()` first, because the accumulated weight is approximate until
     * Caffeine drains its bounded write buffer, and a freshly filled cache would report zero.
     * `weightedSize()` is empty only on a cache with no weigher.
     */
    fun weightedBytes(): Long {
        cache.cleanUp()
        val eviction = cache.policy().eviction().orElse(null) ?: return 0L
        return eviction.weightedSize().orElse(0L)
    }

    private companion object {
        /** The meter tag. `cache_gets` below it reports the hit ratio the ceiling buys. */
        const val NAME = "images"
    }
}

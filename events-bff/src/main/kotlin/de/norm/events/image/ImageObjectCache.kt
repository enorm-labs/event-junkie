package de.norm.events.image

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Weigher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.stereotype.Component

/**
 * Keeps recently served image bytes in memory, so the bucket sees one request per object.
 *
 * **Hetzner Object Storage is Ceph on hard disks**, unlike the volumes, which are on SSD. A miss is
 * therefore a seek. `Cache-Control: immutable` protects a page's second visitor. It protects nobody
 * on the first visit, where every image costs a seek (#847).
 *
 * **Bounded by bytes rather than by entries**, because the entries differ by two orders of
 * magnitude. A 192 pixel card is tens of kilobytes and a 1536 pixel detail image is hundreds. A
 * count would cap the memory at whatever the widest images are.
 *
 * **No expiry, deliberately.** A key names a SHA-256 of the bytes, so one key can only ever return
 * one file. There is nothing for a time bound to protect against.
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
     * Returns [storageKey]'s bytes, calling [load] only when this process does not hold them.
     *
     * **Only [ImageObject.Found] is kept.** A missing object and an unreachable store are both
     * states the next request should ask about again — caching either would outlast the fault and
     * turn a transient bucket problem into a lasting one.
     *
     * Two concurrent misses on one key both call [load]. That costs one extra fetch, and it avoids
     * holding a lock across a network call. Being wrong costs a repeated read.
     */
    suspend fun get(
        storageKey: String,
        load: suspend (String) -> ImageObject
    ): ImageObject {
        cache.getIfPresent(storageKey)?.let { return ImageObject.Found(it) }
        return load(storageKey).also { if (it is ImageObject.Found) cache.put(storageKey, it.bytes) }
    }

    /**
     * Bytes currently held, which is the unit [ImageServingProperties.Cache.size] is set in.
     *
     * **`cache_size` from `CaffeineCacheMetrics` is the entry count, not this.** For a cache bounded
     * by weight the two answer different questions, and only this one says whether the ceiling is
     * reached. [ImageServingMetrics] publishes it as a gauge.
     *
     * Reading it is in-memory work, so unlike the importer's gauges this one is safe to evaluate at
     * scrape time. **`cleanUp()` first, because the accumulated weight is only approximate until
     * Caffeine drains its write buffer** — that buffer is bounded, so the cost is a few entries at
     * most, and without it a freshly filled cache reports zero bytes.
     *
     * `weightedSize()` is empty on a cache with no weigher, which cannot happen here — the zero is
     * for the type, not for a case that occurs.
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

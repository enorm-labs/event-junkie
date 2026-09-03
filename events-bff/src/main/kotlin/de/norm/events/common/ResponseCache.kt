package de.norm.events.common

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Weigher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Keeps assembled read responses in memory, so a repeated question reaches the database once.
 *
 * The read patterns are extremely repetitive — "what is on tonight" is one query for everyone who
 * opens the site that evening — and the data changes at most once per import cycle, which defaults
 * to daily per source (#269).
 *
 * **Invalidation is a time bound, and nothing happens the moment an import finishes.** The importer
 * is a different pod and the BFF runs two replicas, so there is no in-process event to subscribe to
 * and no shared cache to invalidate. `event_source.last_success_at` would make a real watermark, and
 * reading it per request is the query this exists to avoid. Against a daily cycle the TTL bounds
 * staleness at a fraction of a percent.
 *
 * **One cache with one budget, bounded by items rather than entries.** Entries differ by orders of
 * magnitude: a page holds at most `app.api.max-page-size` responses and the calendar holds up to 92
 * days unpaged, so a count would size the cache for the largest thing anyone asked for. Measured at
 * the bound, 20,000 items cost 14MB of a heap that is 75% of a 768Mi limit.
 *
 * **This is the reason it is not `@Cacheable`,** which does work on suspend functions. That gives one
 * cache per method, so the bound becomes a per-cache number times however many caches exist, kept in
 * step by hand. Here the budget is shared and there is one number to reason about.
 *
 * **Two concurrent misses on one key both load**, exactly as [de.norm.events.image.ImageObjectCache]
 * does, and exactly as Spring's own caching does. One extra query is the cheaper failure.
 *
 * **`cache_puts_total` stays at zero and is not the meter to read.** Micrometer derives it from
 * Caffeine's load count, which only moves for a cache built with a loader. `cache_gets_total` and
 * its `result` tag are what report whether this is working.
 */
@Component
class ResponseCache(
    registry: MeterRegistry,
    @Value("\${app.api.cache.ttl-seconds}") ttlSeconds: Long,
    @Value("\${app.api.cache.maximum-items}") maximumItems: Long
) {
    private val cache =
        CaffeineCacheMetrics.monitor(
            registry,
            Caffeine
                .newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumWeight(maximumItems)
                .weigher(Weigher<Any, Any> { _, value -> itemsIn(value) })
                // Required for the meters to report anything. Caffeine counts nothing without it.
                .recordStats()
                .build<Any, Any>(),
            NAME
        )

    /**
     * Returns [key]'s response, calling [load] only when this process does not hold one.
     *
     * [key] is a data class declared by the endpoint it belongs to. Two endpoints therefore cannot
     * collide even when their arguments match, because a data class is equal only to its own type.
     *
     * **[load] runs in the caller's coroutine**, which is what keeps a cached call indistinguishable
     * from an uncached one: the transaction, the request's log context and cancellation all behave
     * as they would without this class in the path.
     *
     * A [load] that throws leaves the cache untouched, so an error is never served twice.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <V : Any> get(
        key: Any,
        load: suspend () -> V
    ): V {
        cache.getIfPresent(key)?.let { return it as V }
        return load().also { cache.put(key, it) }
    }

    /**
     * Entries currently held, for the tests. The meters report the rest.
     *
     * Caffeine evicts on its own maintenance schedule, so this settles that work first — otherwise
     * a size read straight after a write reports whatever the housekeeping has reached.
     */
    fun size(): Long {
        cache.cleanUp()
        return cache.estimatedSize()
    }

    /** Drops everything, for the tests. Nothing in production invalidates by hand. */
    fun clear() = cache.invalidateAll()

    private companion object {
        const val NAME = "responses"

        /**
         * What one entry costs, counted in the responses it carries rather than in bytes. Measuring
         * an object graph would need an agent, and the item count tracks the cost closely enough:
         * every entry here is one summary, one detail, or a list of them.
         */
        fun itemsIn(value: Any): Int =
            when (value) {
                is PageResponse<*> -> value.content.size
                is Collection<*> -> value.size
                else -> 1
            }.coerceAtLeast(1)
    }
}

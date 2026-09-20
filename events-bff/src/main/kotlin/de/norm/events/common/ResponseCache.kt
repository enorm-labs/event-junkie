package de.norm.events.common

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Weigher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Keeps assembled read responses in memory, so a repeated question reaches the database once:
 * "what is on tonight" is one query for everyone, and the data changes at most once per import
 * cycle, daily per source (#269).
 *
 * Invalidation is a time bound: the importer is a different pod and the BFF runs two replicas,
 * so there is no event to subscribe to, and reading `event_source.last_success_at` per request is
 * the query this exists to avoid. Against a daily cycle the TTL bounds staleness at a fraction
 * of a percent.
 *
 * One cache with one budget, bounded by items rather than entries: a page holds at most
 * `app.api.max-page-size` responses and the calendar up to 92 days unpaged, so a count would
 * size the cache for the largest thing anyone asked for. Measured at the bound, 20,000 items
 * cost 14MB. This is why it is not `@Cacheable`, which gives one cache per method and a bound
 * kept in step by hand.
 *
 * Two concurrent misses on one key both load, as [de.norm.events.image.ImageObjectCache] and
 * Spring's own caching do. `cache_puts_total` stays at zero: Micrometer derives it from
 * Caffeine's load count, which only moves with a loader. `cache_gets_total` and its `result`
 * tag report whether this is working.
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
     * Returns [key]'s response, calling [load] only when this process does not hold one. [key] is a
     * data class declared by its endpoint, so two endpoints cannot collide. [load] runs in the
     * caller's coroutine, so the transaction, the log context and cancellation behave as without
     * this class. A [load] that throws leaves the cache untouched.
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
     * Entries currently held, for the tests. Caffeine evicts on its own schedule, so this settles
     * that work first.
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
         * What one entry costs, counted in the responses it carries: measuring an object graph would
         * need an agent, and every entry is one summary, one detail, or a list of them.
         */
        fun itemsIn(value: Any): Int =
            when (value) {
                is PageResponse<*> -> value.content.size
                is Collection<*> -> value.size
                else -> 1
            }.coerceAtLeast(1)
    }
}

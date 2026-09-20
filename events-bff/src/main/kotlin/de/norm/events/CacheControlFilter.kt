package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Puts a `Cache-Control` header on every read response that does not carry one: a response
 * served from a browser's own cache never reaches the cluster, the half of the caching work
 * [de.norm.events.common.ResponseCache] cannot do (#269). The two share
 * `app.api.cache.ttl-seconds`, so one number bounds staleness end to end, at twice its value in
 * the worst case.
 *
 * `beforeCommit` rather than a header set up front, so a handler with its own answer keeps it:
 * the image route marks content named by its own hash `immutable` for a year. A non-success
 * answer gets `no-store`, since a shared cache may apply a heuristic lifetime, and an event
 * published a minute ago would keep being reported missing. Actuator responses are left alone,
 * as [RequestLoggingFilter] leaves them, with the base path read from the property.
 */
@Component
class CacheControlFilter(
    @Value("\${app.api.cache.ttl-seconds}") ttlSeconds: Long,
    @Value("\${management.endpoints.web.base-path:/actuator}") private val actuatorBasePath: String
) : WebFilter {
    private val publicCaching = CacheControl.maxAge(Duration.ofSeconds(ttlSeconds)).cachePublic().headerValue

    @Suppress("ForbiddenVoid") // Mono<Void> is WebFilter's own return type.
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        val request = exchange.request
        if (request.method != HttpMethod.GET || isActuatorRequest(request.path.value())) return chain.filter(exchange)

        val response = exchange.response
        response.beforeCommit {
            if (!response.headers.containsHeader(HttpHeaders.CACHE_CONTROL)) {
                val status = response.statusCode
                val value = if (status != null && status.is2xxSuccessful) publicCaching else NO_STORE
                if (value != null) response.headers.set(HttpHeaders.CACHE_CONTROL, value)
            }
            Mono.empty()
        }
        return chain.filter(exchange)
    }

    /** Matches the base path and everything under it, never a route that merely starts the same. */
    private fun isActuatorRequest(path: String): Boolean {
        val base = actuatorBasePath.removeSuffix("/")
        return path == base || path.startsWith("$base/")
    }

    private companion object {
        val NO_STORE: String? = CacheControl.noStore().headerValue
    }
}

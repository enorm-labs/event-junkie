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
 * published a minute ago would keep being reported missing. Every read also carries
 * `Vary: Accept-Encoding`, because Netty gzips it for some clients only and does not say so (#1206).
 * Actuator responses are left alone, as [RequestLoggingFilter] leaves them, with the base path
 * read from the property.
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
        val response = exchange.response
        // A signed-in visitor's answer, or one that sets a cookie: no cache may keep it, whatever the method or status (ADR-053).
        if (isAccountPath(request.path.value())) {
            response.beforeCommit {
                response.headers.set(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
                Mono.empty()
            }
            return chain.filter(exchange)
        }
        if (request.method != HttpMethod.GET || isActuatorPath(request.path.value(), actuatorBasePath)) return chain.filter(exchange)

        response.beforeCommit {
            if (!response.headers.containsHeader(HttpHeaders.CACHE_CONTROL)) {
                val status = response.statusCode
                val value = if (status != null && status.is2xxSuccessful) publicCaching else NO_STORE
                if (value != null) response.headers.set(HttpHeaders.CACHE_CONTROL, value)
            }
            if (response.headers.vary.none { it.equals(HttpHeaders.ACCEPT_ENCODING, ignoreCase = true) }) {
                response.headers.add(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
            }
            Mono.empty()
        }
        return chain.filter(exchange)
    }

    private companion object {
        val NO_STORE: String? = CacheControl.noStore().headerValue
        const val PRIVATE_NO_STORE = "private, no-store"

        fun isAccountPath(path: String) = path.startsWith("/api/auth/") || path == "/api/me" || path.startsWith("/api/me/")
    }
}

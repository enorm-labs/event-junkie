package de.norm.events

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Answers `400` to any request whose path or query carries a NUL byte. PostgreSQL rejects U+0000
 * with `22021 invalid byte sequence`, which R2DBC surfaces as a `BadSqlGrammarException`, so
 * `?q=%00` answered `500` and logged the whole statement at ERROR (#1441). The body is a
 * constant RFC 9457 problem and echoes nothing; a filter cannot throw into
 * `GlobalExceptionHandler`. A parameter without `=` (`?-s`) arrives as a `null` value, not a NUL
 * (#1465).
 */
@Component
class NulByteFilter : WebFilter {
    @Suppress("ForbiddenVoid") // Mono<Void> is WebFilter's own return type.
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        val request = exchange.request

        // The declared value type is `String`, and `?-s` puts a `null` in the list anyway (#1465).
        @Suppress("UselessCallOnNotNull")
        val tainted =
            (request.uri.path ?: "").contains(NUL) ||
                request.queryParams.any { (name, values) -> name.contains(NUL) || values.any { it.orEmpty().contains(NUL) } }
        if (!tainted) return chain.filter(exchange)

        val response = exchange.response
        response.statusCode = HttpStatus.BAD_REQUEST
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        val body: DataBuffer = response.bufferFactory().wrap(PROBLEM.toByteArray())
        return response.writeWith(Mono.just(body))
    }

    private companion object {
        const val NUL = '\u0000'
        const val PROBLEM =
            """{"type":"about:blank","title":"NUL byte in request","status":400,""" +
                """"detail":"The path or a query parameter contains a NUL byte, which no value here can hold."}"""
    }
}

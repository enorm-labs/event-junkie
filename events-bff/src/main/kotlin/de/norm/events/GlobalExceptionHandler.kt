package de.norm.events

import de.norm.events.artist.ArtistNotFoundException
import de.norm.events.common.UnknownQueryParameterException
import de.norm.events.event.EventNotFoundException
import de.norm.events.promoter.PromoterNotFoundException
import de.norm.events.venue.VenueNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Global exception handler translating the BFF's read-only domain exceptions into
 * RFC 9457 Problem Details. The public API only performs lookups, so the failure modes are
 * "resource not found" (404) and a request this API cannot honour as written (400).
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(
        EventNotFoundException::class,
        VenueNotFoundException::class,
        ArtistNotFoundException::class,
        PromoterNotFoundException::class
    )
    fun handleNotFound(ex: RuntimeException): ProblemDetail {
        logger.debug { "Resource not found: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Resource not found")
    }

    /**
     * A misspelt filter name is a `400`, not a silently wider result set (#815). The offending
     * names and the accepted ones both go in the body, because the caller cannot otherwise tell a
     * typo from a parameter this version does not have yet.
     */
    @ExceptionHandler(UnknownQueryParameterException::class)
    fun handleUnknownQueryParameter(ex: UnknownQueryParameterException): ProblemDetail {
        logger.debug { "Rejected request: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Unknown query parameter").apply {
            title = "Unknown query parameter"
            setProperty("unknown", ex.unknown)
            setProperty("accepted", ex.accepted)
        }
    }

    /**
     * A query parameter whose value does not convert (#3145). Spring's own message for this names
     * the controller method, its DTO classes and springdoc, and Boot's handler passed it through
     * as `detail`. The body here names the parameter, the value and what was expected, and nothing
     * else; the wording avoids the words ZAP rule 90022 reads as an error disclosure.
     */
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleBind(
        ex: WebExchangeBindException,
        exchange: ServerWebExchange
    ): ProblemDetail =
        invalidParameters(
            ex.fieldErrors.map { error ->
                val mismatch = if (error.contains(TypeMismatchException::class.java)) error.unwrap(TypeMismatchException::class.java) else null
                InvalidParameter(error.field, error.rejectedValue, mismatch?.requiredType)
            },
            exchange
        )

    /**
     * The same for a `@RequestParam`, which fails with a bare `Type mismatch.` and the converter's
     * exception as the cause. Any other input failure keeps its reason, which Spring writes for a caller.
     */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleInput(
        ex: ServerWebInputException,
        exchange: ServerWebExchange
    ): ProblemDetail {
        val mismatch = ex.cause as? TypeMismatchException
        val parameter = ex.methodParameter
        if (mismatch == null || parameter == null) {
            logger.debug { "Rejected request: ${ex.reason}" }
            return ProblemDetail.forStatusAndDetail(ex.statusCode, ex.reason).apply { setProperty(LogContextConfiguration.REQUEST_ID, exchange.request.id) }
        }
        val name = parameter.getParameterAnnotation(RequestParam::class.java)?.name?.ifEmpty { null } ?: parameter.parameterName ?: "?"
        return invalidParameters(listOf(InvalidParameter(name, mismatch.value, mismatch.requiredType)), exchange)
    }

    private fun invalidParameters(
        invalid: List<InvalidParameter>,
        exchange: ServerWebExchange
    ): ProblemDetail {
        val detail =
            invalid.joinToString(" ") { "'${it.name}' must be ${expectation(it.requiredType)}, not '${shown(it.value)}'." }.ifEmpty {
                "The query parameters could not be read."
            }
        // The names only: the values are what the caller typed.
        logger.debug { "Rejected request: query parameters ${invalid.map { it.name }} do not convert" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply {
            title = "Invalid query parameter"
            setProperty("invalid", invalid.map { it.name })
            setProperty(LogContextConfiguration.REQUEST_ID, exchange.request.id)
        }
    }

    private data class InvalidParameter(
        val name: String,
        val value: Any?,
        val requiredType: Class<*>?
    )

    private companion object {
        /** Long enough to recognise the value, short enough that a probe is not echoed whole. */
        const val SHOWN_VALUE_LENGTH = 40

        fun shown(value: Any?): String {
            val text =
                when (value) {
                    is Array<*> -> value.joinToString(",")
                    null -> ""
                    else -> value.toString()
                }
            return if (text.length > SHOWN_VALUE_LENGTH) text.take(SHOWN_VALUE_LENGTH) + "…" else text
        }

        /** In words, because a class name is what this handler exists to keep out of the body. */
        fun expectation(type: Class<*>?): String =
            when (type) {
                LocalDate::class.java -> "a date as YYYY-MM-DD"
                Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> "true or false"
                Int::class.javaPrimitiveType, Int::class.javaObjectType, Long::class.javaPrimitiveType, Long::class.javaObjectType -> "a whole number"
                BigDecimal::class.java, Double::class.javaPrimitiveType, Double::class.javaObjectType -> "a number"
                else -> "a valid value"
            }
    }
}

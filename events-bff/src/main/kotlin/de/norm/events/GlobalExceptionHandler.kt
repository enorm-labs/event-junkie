package de.norm.events

import de.norm.events.artist.ArtistNotFoundException
import de.norm.events.common.UnknownQueryParameterException
import de.norm.events.event.EventNotFoundException
import de.norm.events.promoter.PromoterNotFoundException
import de.norm.events.venue.VenueNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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
}

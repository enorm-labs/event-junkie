package de.norm.events

import de.norm.events.artist.ArtistNotFoundException
import de.norm.events.artist.DuplicateArtistSlugException
import de.norm.events.event.EventNotFoundException
import de.norm.events.genretag.GenreTagNotFoundException
import de.norm.events.promoter.DuplicatePromoterSlugException
import de.norm.events.promoter.PromoterNotFoundException
import de.norm.events.scraper.EventSourceNotFoundException
import de.norm.events.scraper.InvalidSourceTypeException
import de.norm.events.scraper.ReservedSlugException
import de.norm.events.venue.DuplicateVenueSlugException
import de.norm.events.venue.VenueNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.TypeMismatchException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebInputException
import tools.jackson.databind.exc.UnrecognizedPropertyException

/**
 * Global exception handler that translates domain exceptions into
 * structured HTTP error responses using RFC 9457 Problem Details.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(
        VenueNotFoundException::class,
        ArtistNotFoundException::class,
        PromoterNotFoundException::class,
        EventNotFoundException::class,
        EventSourceNotFoundException::class,
        GenreTagNotFoundException::class
    )
    fun handleNotFound(ex: RuntimeException): ProblemDetail {
        logger.debug { "Entity not found: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Entity not found")
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ProblemDetail {
        logger.warn { "Data integrity violation: ${ex.message}" }
        val detail = extractConstraintDetail(ex)
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail)
    }

    /**
     * Handles duplicate slug violations from the service-layer pre-check — these provide
     * a more descriptive error message than the generic DB constraint handler above,
     * identifying the entity type, conflicting slug, and the name that produced it.
     */
    @ExceptionHandler(
        DuplicateArtistSlugException::class,
        DuplicateVenueSlugException::class,
        DuplicatePromoterSlugException::class
    )
    fun handleDuplicateSlug(ex: RuntimeException): ProblemDetail {
        logger.debug { "Duplicate slug: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "A record with the same slug already exists.")
    }

    /**
     * Inspects the exception cause chain for a Postgres-specific constraint name
     * and returns a user-friendly message identifying which unique constraint was violated.
     * Falls back to a generic message if the constraint name cannot be determined.
     */
    private fun extractConstraintDetail(ex: DataIntegrityViolationException): String {
        val message = ex.mostSpecificCause.message ?: return DEFAULT_CONFLICT_MESSAGE
        // Postgres unique violation messages contain: Key (<column>)=(<value>) already exists.
        // The constraint name appears after "constraint" in the detail, e.g.:
        //   "ERROR: duplicate key value violates unique constraint "artist_slug_key""
        val constraintName = CONSTRAINT_NAME_PATTERN.find(message)?.groupValues?.get(1)
        return when {
            constraintName?.contains("source_id") == true -> "A record with the same source ID already exists."
            constraintName?.contains("slug") == true -> "A record with the same slug already exists."
            constraintName != null -> "Duplicate value violates unique constraint: $constraintName"
            else -> DEFAULT_CONFLICT_MESSAGE
        }
    }

    companion object {
        private const val DEFAULT_CONFLICT_MESSAGE = "A record with the same unique identifier already exists."

        /** Matches the constraint name in Postgres error messages like: unique constraint "artist_slug_key" */
        private val CONSTRAINT_NAME_PATTERN = Regex("""unique constraint "(\w+)"""")
    }

    /**
     * Translates Bean Validation failures into a structured RFC 9457 Problem Detail
     * with an `errors` extension property containing per-field error details,
     * making it easy for API consumers to programmatically identify which fields failed.
     */
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidation(ex: WebExchangeBindException): ProblemDetail {
        val fieldErrors = ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) }
        logger.debug { "Validation failed: ${fieldErrors.joinToString("; ") { "${it["field"]}: ${it["message"]}" }}" }
        val detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed")
        detail.setProperty("errors", fieldErrors)
        return detail
    }

    /**
     * Handles reserved slug violations — the source name generates a slug that conflicts
     * with a reserved API path segment (e.g. "import", "retry"). This is a client input error.
     */
    @ExceptionHandler(ReservedSlugException::class)
    fun handleReservedSlug(ex: ReservedSlugException): ProblemDetail {
        logger.debug { "Reserved slug violation: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Reserved slug")
    }

    /**
     * Handles invalid source type values — the client provided a `sourceType` that doesn't
     * match any known [de.norm.events.scraper.EventSource] enum constant.
     */
    @ExceptionHandler(InvalidSourceTypeException::class)
    fun handleInvalidSourceType(ex: InvalidSourceTypeException): ProblemDetail {
        logger.debug { "Invalid source type: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid source type")
    }

    /**
     * Handles malformed request input that WebFlux rejects before the controller runs —
     * most often a path variable or query parameter that cannot be converted to its declared
     * type (`GET /api/admin/venues/bar-jeder-vernunft` against a `Long` id), but also an
     * unreadable request body.
     *
     * This handler exists to stop [handleIllegalArgument] claiming those requests. WebFlux
     * already raises a `ServerWebInputException` carrying **400**, but its cause chain ends
     * in the converter's `NumberFormatException` — an [IllegalArgumentException] — and
     * Spring's handler lookup falls back to the cause when no handler matches the thrown
     * type. That turned a correct 400 into a 500 whose detail was the raw converter message
     * ("For input string: \"bar-jeder-vernunft\""). Declaring the thrown type explicitly wins
     * the lookup, because an exact-type match takes precedence over a cause-chain match.
     *
     * The status is taken from the exception rather than hard-coded, so a subclass carrying a
     * different code keeps it. [WebExchangeBindException] is such a subclass and continues to
     * be handled by the more specific [handleValidation], by the same precedence rule.
     */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleInvalidInput(ex: ServerWebInputException): ProblemDetail {
        logger.debug { "Invalid request input: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(ex.statusCode, describeInvalidInput(ex))
    }

    /**
     * Builds a client-facing description of an input failure.
     *
     * A type mismatch names the rejected value and the type expected — the raw
     * `"Type mismatch."` reason alone leaves the caller guessing which of several parameters
     * was wrong. An unknown property names the field and what the endpoint does accept, because
     * the whole point of rejecting it is that the caller can see the typo (#814). Anything else
     * falls back to the exception's own reason.
     */
    private fun describeInvalidInput(ex: ServerWebInputException): String {
        val unknownProperty = ex.findCause<UnrecognizedPropertyException>()
        if (unknownProperty != null) {
            val known =
                unknownProperty.knownPropertyIds
                    .orEmpty()
                    .map { it.toString() }
                    .sorted()
            return "Unknown field '${unknownProperty.propertyName}'." +
                if (known.isEmpty()) "" else " Accepted: ${known.joinToString(", ")}."
        }
        val mismatch = ex.cause as? TypeMismatchException
        // Capitalised so a Kotlin `Long` id reads as its declared type, not the JVM primitive "long".
        val requiredType = mismatch?.requiredType?.simpleName?.replaceFirstChar { it.uppercase() }
        return if (mismatch != null && requiredType != null) {
            "Invalid value '${mismatch.value}': expected a valid $requiredType."
        } else {
            ex.reason ?: "Request input is invalid."
        }
    }

    /**
     * Walks the cause chain for [T]. Jackson's failure arrives wrapped — WebFlux's decoder sits
     * between it and the handler — so `cause as?` alone finds nothing.
     */
    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    /**
     * Handles [IllegalArgumentException] thrown when the database contains an enum value
     * that doesn't match any Kotlin enum constant (e.g. an unknown [de.norm.events.event.ArtistRole] or [de.norm.events.event.EventType]
     * from a manual DB edit or future migration).
     *
     * Deliberately last-resort: because Spring falls back to the cause chain, this would also
     * swallow framework exceptions merely *caused* by an [IllegalArgumentException]. Any such
     * family that is really a client error needs its own handler above — see
     * [handleInvalidInput].
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        logger.error { "Illegal argument encountered: ${ex.message}" }
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ex.message ?: "An unexpected data inconsistency was detected."
        )
    }
}

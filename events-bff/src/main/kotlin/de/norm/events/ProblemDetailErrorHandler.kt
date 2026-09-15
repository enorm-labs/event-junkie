package de.norm.events

import org.springframework.boot.autoconfigure.web.ErrorProperties
import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.webflux.autoconfigure.error.DefaultErrorWebExceptionHandler
import org.springframework.boot.webflux.error.ErrorAttributes
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.reactive.function.server.RequestPredicates.all
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.net.URI

/**
 * Renders every error that reaches Boot's error handler as an RFC 9457 problem, whatever the
 * client accepts.
 *
 * [GlobalExceptionHandler] only sees exceptions thrown by a handler. A route that does not exist,
 * a parameter that does not bind and anything a filter throws land here instead, where Boot's
 * default answered `text/html` clients with the Whitelabel page — the framework's name, a server
 * timestamp — and everyone else with a JSON shape of its own (#1446). One renderer, one shape.
 *
 * `detail` carries the exception message for a 4xx, which is the client's own mistake, and a
 * fixed sentence for a 5xx, so nothing internal is written into a response. Boot's status
 * resolution and its logging are inherited untouched.
 */
class ProblemDetailErrorHandler(
    errorAttributes: ErrorAttributes,
    resources: WebProperties.Resources,
    errorProperties: ErrorProperties,
    applicationContext: ApplicationContext
) : DefaultErrorWebExceptionHandler(errorAttributes, resources, errorProperties, applicationContext) {
    override fun getRoutingFunction(errorAttributes: ErrorAttributes): RouterFunction<ServerResponse> = route(all(), ::renderProblem)

    private fun renderProblem(request: ServerRequest): Mono<ServerResponse> {
        // Boot's own options, plus the message: `detail` below decides per status whether it is shown.
        val options = getErrorAttributeOptions(request, MediaType.ALL).including(ErrorAttributeOptions.Include.MESSAGE)
        val attributes = getErrorAttributes(request, options)
        val status = getHttpStatus(attributes)
        val problem = problemFor(status, attributes["message"] as? String, request.uri().rawPath, attributes["requestId"] as? String)
        return ServerResponse
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .bodyValue(problem)
    }

    internal companion object {
        const val SERVER_ERROR_DETAIL = "The request could not be processed. Quote the request id when reporting it."

        /** Pure, so the 5xx branch is testable without provoking a 5xx from outside. */
        fun problemFor(
            status: Int,
            message: String?,
            rawPath: String,
            requestId: String?
        ): ProblemDetail {
            val resolved = HttpStatus.resolve(status)
            return ProblemDetail.forStatus(status).apply {
                title = resolved?.reasonPhrase ?: "Error"
                detail = if (status < HttpStatus.INTERNAL_SERVER_ERROR.value()) message?.takeIf { it.isNotBlank() } else SERVER_ERROR_DETAIL
                instance = URI.create(rawPath)
                requestId?.let { setProperty("requestId", it) }
            }
        }
    }
}

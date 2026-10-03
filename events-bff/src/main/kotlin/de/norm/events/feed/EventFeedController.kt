package de.norm.events.feed

import de.norm.events.common.QueryParameters
import de.norm.events.common.ResponseCache
import de.norm.events.common.Site
import de.norm.events.event.BffMetrics
import de.norm.events.event.EventFilter
import de.norm.events.event.EventFilterParams
import de.norm.events.event.EventService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * The RSS feed of new events (#368): the events the importer stored most recently, newest first.
 * It takes the list's filters, so a filtered feed URL is a saved search a reader polls.
 */
@RestController
@RequestMapping("/api/events/feed")
@Tag(name = "Feed", description = "An RSS feed of newly imported events, with the same filters as the event list")
class EventFeedController(
    private val eventService: EventService,
    private val metrics: BffMetrics,
    private val cache: ResponseCache,
    @Value("\${app.api.cache.ttl-seconds}") ttlSeconds: Long
) {
    /** Set here rather than by the filter, so a `304` keeps it: the filter gives a non-`2xx` answer `no-store`. */
    private val cacheControl = CacheControl.maxAge(Duration.ofSeconds(ttlSeconds)).cachePublic()

    @GetMapping
    @Operation(summary = "Get the newest events as an RSS 2.0 feed, at most 50, leaving out those that are over")
    @ApiResponse(
        responseCode = "200",
        description = "The feed",
        content = [Content(mediaType = EventFeedXml.CONTENT_TYPE, schema = Schema(type = "string"))]
    )
    suspend fun feed(
        @Parameter(description = "Language of the feed's text and links: en or de.", example = "de")
        @RequestParam(defaultValue = Site.DEFAULT_LOCALE)
        locale: String,
        @ParameterObject
        filters: EventFilterParams,
        exchange: ServerWebExchange
    ): ResponseEntity<String> {
        PARAMS.rejectUnknownIn(exchange)
        if (locale !in Site.LOCALES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'locale' must be one of ${Site.LOCALES.joinToString(", ")}")
        }
        val filter = filters.toFilter()
        val events = cache.get(FeedKey(filter)) { eventService.newest(filter, MAX_ITEMS) }
        metrics.recordServed(BffMetrics.ENDPOINT_FEED, events.size)
        val body = EventFeedXml.render(locale, selfUrl(exchange), events)
        // A reader polls with If-None-Match; Spring answers 304 for a matching tag.
        return ResponseEntity
            .ok()
            .cacheControl(cacheControl)
            .eTag(weakTagOf(body))
            .contentType(MediaType.parseMediaType("${EventFeedXml.CONTENT_TYPE};charset=UTF-8"))
            .body(body)
    }

    /** The public address of this request: the site's feed path, never the request's host or `/api` path. */
    private fun selfUrl(exchange: ServerWebExchange): String {
        val query = exchange.request.uri.rawQuery
        return "${Site.URL}${Site.FEED_PATH}${if (query == null) "" else "?$query"}"
    }

    /** Weak, because the server compresses for some clients only and the bytes differ. */
    private fun weakTagOf(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        return "W/\"${HexFormat.of().formatHex(digest, 0, TAG_BYTES)}\""
    }

    private companion object {
        /** A reader keeps what it has seen, so the cap bounds the document, not what an hourly reader learns. */
        const val MAX_ITEMS = 50
        const val TAG_BYTES = 16

        val PARAMS = QueryParameters.accepting(EventFilterParams::class.java, QueryParameters.named("locale"))
    }
}

/** The feed's cache key. The locale is not in it: the events are the same, only the rendering differs. */
private data class FeedKey(
    val filter: EventFilter
)

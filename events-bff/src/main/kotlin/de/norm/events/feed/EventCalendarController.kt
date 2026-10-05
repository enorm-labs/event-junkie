package de.norm.events.feed

import de.norm.events.common.QueryParameters
import de.norm.events.common.ResponseCache
import de.norm.events.common.Site
import de.norm.events.common.WeakETag
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
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * The calendar subscription (#2719): the events of the coming days as an iCalendar file a calendar
 * app polls. It takes the list's filters, so a filtered URL is a saved search, as the RSS feed is.
 */
@RestController
@RequestMapping("/api/events/calendar.ics")
@Tag(name = "Feed", description = "Feeds a reader or a calendar polls: RSS of new events, iCalendar of the coming days, with the list's filters")
class EventCalendarController(
    private val eventService: EventService,
    private val metrics: BffMetrics,
    private val cache: ResponseCache,
    private val clock: Clock,
    @Value("\${app.api.cache.ttl-seconds}") ttlSeconds: Long
) {
    /** Set here rather than by the filter, so a `304` keeps it: the filter gives a non-`2xx` answer `no-store`. */
    private val cacheControl = CacheControl.maxAge(Duration.ofSeconds(ttlSeconds)).cachePublic()

    @GetMapping
    @Operation(
        summary = "Get the events from today for 90 days as an iCalendar (RFC 5545) subscription, at most 500, in the list's order"
    )
    @ApiResponse(
        responseCode = "200",
        description = "The calendar",
        content = [Content(mediaType = EventCalendarIcs.CONTENT_TYPE, schema = Schema(type = "string"))]
    )
    suspend fun calendar(
        @Parameter(description = "Language of the entries' links and notes: en or de.", example = "de")
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
        val today = LocalDate.now(clock)
        val last = today.plusDays(DAYS - 1)
        val events = cache.get(CalendarKey(filter, today)) { eventService.upcoming(filter, today, last, MAX_EVENTS) }
        metrics.recordServed(BffMetrics.ENDPOINT_CALENDAR_FEED, events.size)
        val body = EventCalendarIcs.render(locale, events)
        // A client polls with If-None-Match; Spring answers 304 for a matching tag.
        return ResponseEntity
            .ok()
            .cacheControl(cacheControl)
            .eTag(WeakETag.of(body))
            .contentType(MediaType.parseMediaType("${EventCalendarIcs.CONTENT_TYPE};charset=UTF-8"))
            .body(body)
    }

    private companion object {
        /** Today and the 89 days after it. */
        const val DAYS = 90L

        /** Bounds the file a client parses on every poll; a busy unfiltered quarter is more. */
        const val MAX_EVENTS = 500

        val PARAMS = QueryParameters.accepting(EventFilterParams::class.java, QueryParameters.named("locale"))
    }
}

/** The calendar's cache key. The day is in it, because the window moves with it; the locale is not. */
private data class CalendarKey(
    val filter: EventFilter,
    val today: LocalDate
)

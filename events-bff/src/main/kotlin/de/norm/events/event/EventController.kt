package de.norm.events.event

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Public read API for events: filtered search, today's events, calendar range, and detail by slug.
 */
@RestController
@RequestMapping("/events")
@Tag(name = "Events", description = "Public endpoints for browsing, filtering, and viewing events")
class EventController(
    private val eventService: EventService,
    /** `bff.events.served` (#415). Counts events handed out, not requests — see [BffMetrics]. */
    private val metrics: BffMetrics
) {
    @GetMapping
    @Operation(summary = "Search events with optional filters and pagination")
    suspend fun list(
        @Parameter(description = "Earliest event date (inclusive), ISO-8601 (e.g. 2026-06-19). Defaults to today when both from/to are omitted.")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @Parameter(description = "Latest event date (inclusive), ISO-8601 (e.g. 2026-06-30).")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
        @ParameterObject
        filters: EventFilterParams,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["eventDate"])
        pageable: Pageable
    ): PageResponse<EventSummaryResponse> =
        eventService.search(filters.toFilter(from = from, to = to), pageable).also {
            metrics.recordServed(BffMetrics.ENDPOINT_SEARCH, it.content.size)
        }

    @GetMapping("/today")
    @Operation(summary = "Get today's events")
    suspend fun today(): List<EventSummaryResponse> = eventService.today().also { metrics.recordServed(BffMetrics.ENDPOINT_TODAY, it.size) }

    @GetMapping("/calendar")
    @Operation(summary = "Get events within an inclusive date range for the calendar view, with the same optional filters as the search endpoint")
    suspend fun calendar(
        @Parameter(description = "Range start date (inclusive), ISO-8601.", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
        @Parameter(description = "Range end date (inclusive), ISO-8601. Must not precede 'from' or exceed 92 days from it.", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate,
        @ParameterObject
        filters: EventFilterParams
    ): List<EventSummaryResponse> = eventService.calendar(from, to, filters.toFilter()).also { metrics.recordServed(BffMetrics.ENDPOINT_CALENDAR, it.size) }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a single event by slug")
    suspend fun findBySlug(
        @Parameter(description = "Unique event slug (format: {date}-{venue}-{title}).", example = "2026-06-18-lido-sam-prekop-john-mcentire", required = true)
        @PathVariable slug: String
    ): EventDetailResponse = eventService.findBySlug(slug).also { metrics.recordServed(BffMetrics.ENDPOINT_DETAIL, 1) }
}

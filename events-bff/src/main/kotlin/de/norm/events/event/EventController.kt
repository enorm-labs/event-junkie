package de.norm.events.event

import de.norm.events.common.PageResponse
import de.norm.events.common.QueryParameters
import de.norm.events.common.ResponseCache
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
import org.springframework.web.server.ServerWebExchange
import java.time.Clock
import java.time.LocalDate

/**
 * Public read API for events. Every endpoint reads through [ResponseCache], at this layer
 * because a service calling its own cached method would bypass the `@Transactional` proxy, and
 * caching is a property of the request (#269).
 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Public endpoints for browsing, filtering, and viewing events")
class EventController(
    private val eventService: EventService,
    /** `bff.events.served` (#415). Counts events handed out, not requests — see [BffMetrics]. */
    private val metrics: BffMetrics,
    private val cache: ResponseCache,
    private val clock: Clock
) {
    @GetMapping
    @Operation(
        summary = "Search events with optional filters and pagination",
        description =
            "Sort with `sort=eventDate` (the default, then the start time), `sort=startTime`, `sort=title`, `sort=pricePresale` " +
                "or `sort=createdAt,desc` (newest added first, when the importer first stored the event). Without a date range, " +
                "the list holds only events that have not ended."
    )
    suspend fun list(
        @ParameterObject
        range: EventDateRangeParams,
        @ParameterObject
        filters: EventFilterParams,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["eventDate"])
        pageable: Pageable,
        exchange: ServerWebExchange
    ): PageResponse<EventSummaryResponse> {
        SEARCH_PARAMS.rejectUnknownIn(exchange)
        val filter = filters.toFilter(from = range.from, to = range.to, running = range.running)
        // The meter counts what is handed out, so it stays outside the cache.
        return cache.get(SearchKey(filter, pageable)) { eventService.search(filter, pageable) }.also {
            metrics.recordServed(BffMetrics.ENDPOINT_SEARCH, it.content.size)
        }
    }

    @GetMapping("/today")
    @Operation(summary = "Get today's events")
    suspend fun today(exchange: ServerWebExchange): List<EventSummaryResponse> {
        NO_PARAMS.rejectUnknownIn(exchange)
        // Keyed on the date rather than left to the TTL, so the answer changes at midnight.
        return cache
            .get(TodayKey(LocalDate.now(clock))) { eventService.today() }
            .also { metrics.recordServed(BffMetrics.ENDPOINT_TODAY, it.size) }
    }

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
        filters: EventFilterParams,
        exchange: ServerWebExchange
    ): List<EventSummaryResponse> {
        CALENDAR_PARAMS.rejectUnknownIn(exchange)
        val filter = filters.toFilter()
        return cache
            .get(CalendarKey(from, to, filter)) { eventService.calendar(from, to, filter) }
            .also { metrics.recordServed(BffMetrics.ENDPOINT_CALENDAR, it.size) }
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a single event by slug")
    suspend fun findBySlug(
        @Parameter(description = "Unique event slug (format: {date}-{venue}-{title}).", example = "2026-06-18-lido-sam-prekop-john-mcentire", required = true)
        @PathVariable slug: String
    ): EventDetailResponse =
        cache
            .get(DetailKey(slug)) { eventService.findBySlug(slug) }
            .also { metrics.recordServed(BffMetrics.ENDPOINT_DETAIL, 1) }

    @GetMapping("/{slug}/related")
    @Operation(
        summary = "Get upcoming events like one event",
        description =
            "Up to four events that have not ended and share artists, the venue or genre tags with the event, " +
                "best match first. A shared artist counts most, then the venue, then each shared genre tag. " +
                "Empty when nothing matches; 404 when the slug is unknown."
    )
    suspend fun related(
        @Parameter(description = "Slug of the event to match.", example = "2026-06-18-lido-sam-prekop-john-mcentire", required = true)
        @PathVariable slug: String,
        exchange: ServerWebExchange
    ): List<EventSummaryResponse> {
        NO_PARAMS.rejectUnknownIn(exchange)
        return cache
            .get(RelatedKey(slug)) { eventService.related(slug) }
            .also { metrics.recordServed(BffMetrics.ENDPOINT_RELATED, it.size) }
    }

    private companion object {
        /** The filter fields come from [EventFilterParams]; the names of [EventDateRangeParams] and paging are listed here. */
        val SEARCH_PARAMS =
            QueryParameters.accepting(
                EventFilterParams::class.java,
                QueryParameters.PAGEABLE,
                QueryParameters.named("from", "to", "running")
            )

        /** The calendar shares the filters but pages nothing, and requires its own `from`/`to`. */
        val CALENDAR_PARAMS =
            QueryParameters.accepting(
                EventFilterParams::class.java,
                QueryParameters.named("from", "to")
            )

        /** `/today` and `/{slug}/related` take no parameters at all, so any is a mistake worth reporting. */
        val NO_PARAMS = QueryParameters.accepting()
    }
}

/**
 * The cache keys this controller owns, separate types rather than one key carrying an endpoint
 * name: a data class is equal only to its own type.
 */
private data class SearchKey(
    val filter: EventFilter,
    val pageable: Pageable
)

private data class TodayKey(
    val date: LocalDate
)

private data class CalendarKey(
    val from: LocalDate,
    val to: LocalDate,
    val filter: EventFilter
)

private data class DetailKey(
    val slug: String
)

private data class RelatedKey(
    val slug: String
)

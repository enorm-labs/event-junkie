package de.norm.events.event

import io.swagger.v3.oas.annotations.Parameter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The date-independent filter criteria shared by `GET /events` and `GET /events/calendar`.
 *
 * Bound from the query string as a model attribute; `@ParameterObject` on the method parameter
 * tells springdoc to flatten it back into individual query parameters, so the OpenAPI contract is
 * unchanged from declaring them one by one. Both endpoints offer the same filters — the list and
 * the calendar are two renderings of one search — and sharing this object keeps them from drifting.
 *
 * `from`/`to` deliberately stay on the controller methods: they are optional on the search endpoint
 * but required and range-checked on the calendar, so they are not part of the shared contract.
 *
 * **A name that is not here is a `400`, not a silently ignored parameter** (#815). The endpoints
 * derive their accepted set from this class, so adding a filter needs no second edit — see
 * [de.norm.events.common.QueryParameters] for why rejecting is worth its cost.
 */
@Suppress("LongParameterList")
data class EventFilterParams(
    @field:Parameter(description = "Event type filter, e.g. CONCERT (case-insensitive).")
    val eventType: String? = null,
    @field:Parameter(description = "Venue slug filter — only events at the matching venue.")
    val venue: String? = null,
    @field:Parameter(description = "District filter — only events at venues in the matching Berlin borough (e.g. friedrichshain-kreuzberg).")
    val district: String? = null,
    @field:Parameter(description = "Artist slug filter — only events featuring the matching artist.")
    val artist: String? = null,
    @field:Parameter(description = "Promoter slug filter — only events from the matching promoter.")
    val promoter: String? = null,
    @field:Parameter(description = "Genre tag slug filter — only events tagged with the matching genre.")
    val genre: String? = null,
    @field:Parameter(description = "Minimum presale price (inclusive). Excludes events with an unknown (null) price.")
    val minPrice: BigDecimal? = null,
    @field:Parameter(description = "Maximum presale price (inclusive). Excludes events with an unknown (null) price.")
    val maxPrice: BigDecimal? = null,
    @field:Parameter(description = "Case-insensitive substring search over the event title and subtitle.")
    val q: String? = null,
    @field:Parameter(description = "When true, excludes events flagged as sold out. Defaults to false (include all).")
    val excludeSoldOut: Boolean = false,
    @field:Parameter(description = "When true, returns only events flagged as free to attend. Defaults to false.")
    val free: Boolean = false
) {
    /** Combines these criteria with an optional date range into the repository-level [EventFilter]. */
    fun toFilter(
        from: LocalDate? = null,
        to: LocalDate? = null
    ): EventFilter =
        EventFilter(
            from = from,
            to = to,
            eventType = eventType,
            venueSlug = venue,
            district = district,
            artistSlug = artist,
            promoterSlug = promoter,
            genreSlug = genre,
            minPrice = minPrice,
            maxPrice = maxPrice,
            query = q,
            excludeSoldOut = excludeSoldOut,
            onlyFree = free
        )
}

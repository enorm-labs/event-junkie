package de.norm.events.event

import io.swagger.v3.oas.annotations.Parameter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The date-independent filter criteria shared by `GET /events` and `GET /events/calendar`, two
 * renderings of one search. Bound as a model attribute; `@ParameterObject` tells springdoc to
 * flatten it back into query parameters. `from`/`to` stay on the controller methods: optional on
 * the search, required and range-checked on the calendar. A name that is not here is a `400`
 * (#815); the endpoints derive their accepted set from this class
 * ([de.norm.events.common.QueryParameters]).
 */
@Suppress("LongParameterList")
data class EventFilterParams(
    @field:Parameter(description = "Event type filter, e.g. CONCERT (case-insensitive).")
    val eventType: String? = null,
    @field:Parameter(description = "Venue slug filter — only events at the matching venue.")
    val venue: String? = null,
    @field:Parameter(description = "District filter — only events at venues in the matching pre-2001 Berlin district (e.g. kreuzberg).")
    val district: String? = null,
    @field:Parameter(description = "Artist slug filter — only events featuring the matching artist.")
    val artist: String? = null,
    @field:Parameter(description = "Promoter slug filter — only events from the matching promoter.")
    val promoter: String? = null,
    @field:Parameter(description = "Genre tag slug filter — only events tagged with the matching genre.")
    val genre: String? = null,
    @field:Parameter(description = "Genre family slug filter (e.g. electronic) — only events tagged with a genre in that family.")
    val family: String? = null,
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
            familySlug = family,
            minPrice = minPrice,
            maxPrice = maxPrice,
            query = q,
            excludeSoldOut = excludeSoldOut,
            onlyFree = free
        )
}

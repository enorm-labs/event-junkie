package de.norm.events.event

import de.norm.events.common.TextSearch
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.format.annotation.DateTimeFormat
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The date-independent filter criteria shared by `GET /events` and `GET /events/calendar`, two
 * renderings of one search. Bound as a model attribute; `@ParameterObject` tells springdoc to
 * flatten it back into query parameters. `from`/`to` are not here: the search binds them
 * optional through [EventDateRangeParams], and the calendar requires and range-checks its own.
 * A name that is not here is a `400` (#815); the endpoints derive their accepted set from this
 * class ([de.norm.events.common.QueryParameters]).
 */
@Suppress("LongParameterList")
data class EventFilterParams(
    @field:Parameter(
        description = "Event type filter, e.g. CONCERT (case-insensitive). Repeatable: an event of any given type matches. An unknown type matches nothing."
    )
    val eventType: List<String>? = null,
    @field:Parameter(description = "Venue slug filter — only events at the matching venue.")
    val venue: String? = null,
    @field:Parameter(
        description = "Pre-2001 Berlin district slug (e.g. kreuzberg). Repeatable: an event at a venue in any given district matches."
    )
    val district: List<String>? = null,
    @field:Parameter(
        description = "Venue type slug (e.g. club). Repeatable: an event at a venue of any given type matches. An unknown type matches nothing."
    )
    val venueType: List<String>? = null,
    @field:Parameter(description = "Artist slug filter — only events featuring the matching artist.")
    val artist: String? = null,
    @field:Parameter(description = "Promoter slug filter — only events from the matching promoter.")
    val promoter: String? = null,
    @field:Parameter(description = "Genre tag slug filter — only events tagged with the matching genre.")
    val genre: String? = null,
    @field:Parameter(
        description = "Genre family slug filter (e.g. electronic). Repeatable: an event tagged with a genre in any given family matches."
    )
    val family: List<String>? = null,
    @field:Parameter(description = "Minimum presale price (inclusive). Excludes events with an unknown (null) price.")
    val minPrice: BigDecimal? = null,
    @field:Parameter(description = "Maximum presale price (inclusive). Excludes events with an unknown (null) price.")
    val maxPrice: BigDecimal? = null,
    @field:Parameter(
        description =
            "Search over the event title, subtitle, venue name, lineup and promoters: " +
                "ignores case, accents and spaces, and forgives small typos."
    )
    val q: String? = null,
    @field:Parameter(description = "When true, excludes events flagged as sold out. Defaults to false (include all).")
    val excludeSoldOut: Boolean = false,
    @field:Parameter(description = "When true, returns only events flagged as free to attend. Defaults to false.")
    val free: Boolean = false
) {
    /**
     * Combines these criteria with an optional date range into the repository-level [EventFilter].
     * With [running], [from] means "ends on or after" rather than "starts on or after", the
     * calendar's overlap (#2674).
     */
    fun toFilter(
        from: LocalDate? = null,
        to: LocalDate? = null,
        running: Boolean = false
    ): EventFilter =
        EventFilter(
            from = from.takeUnless { running },
            to = to,
            runningFrom = from.takeIf { running },
            eventTypes = eventType.orEmpty().normalizedEventTypes(),
            venueSlug = venue,
            districts = district.orEmpty().normalizedSlugs(),
            venueTypes = venueType.orEmpty().normalizedSlugs(),
            artistSlug = artist,
            promoterSlug = promoter,
            genreSlug = genre,
            familySlugs = family.orEmpty().normalizedSlugs(),
            minPrice = minPrice,
            maxPrice = maxPrice,
            query = TextSearch.term(q),
            excludeSoldOut = excludeSoldOut,
            onlyFree = free
        )
}

/**
 * The optional date range of `GET /events`. It is bound apart from [EventFilterParams] because
 * the calendar requires its own `from`/`to`, and the calendar and the feed reject `running`
 * with a `400`.
 */
data class EventDateRangeParams(
    @field:Parameter(description = "Earliest event date (inclusive), ISO-8601 (e.g. 2026-06-19). Defaults to today when both from/to are omitted.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val from: LocalDate? = null,
    @field:Parameter(description = "Latest event date (inclusive), ISO-8601 (e.g. 2026-06-30).")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val to: LocalDate? = null,
    @field:Parameter(
        description =
            "When true, 'from' keeps every event still running on that day: one that ends on or after 'from' and starts on or before 'to'. " +
                "Without it, 'from' is the earliest start date. Defaults to false."
    )
    val running: Boolean = false
)

/**
 * Trimmed, upper-cased, de-duplicated and sorted, so two orders of the same types are one
 * [EventFilter] and share a response-cache entry.
 */
private fun List<String>.normalizedEventTypes(): List<String> = map { it.uppercase() }.normalizedSlugs()

/** Trimmed, de-duplicated and sorted, for the same cache-key reason as [normalizedEventTypes]. */
private fun List<String>.normalizedSlugs(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

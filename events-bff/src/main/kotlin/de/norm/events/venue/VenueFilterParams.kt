package de.norm.events.venue

import io.swagger.v3.oas.annotations.Parameter

/**
 * The venue list's filter criteria, bound as a model attribute like `EventFilterParams`. A name that
 * is not here, and not paging, is a `400`; the controller derives its accepted set from this class.
 */
data class VenueFilterParams(
    @field:Parameter(description = "Case-insensitive substring filter on the venue name. Omitted/blank returns all venues.")
    val q: String? = null,
    @field:Parameter(
        description =
            "District filter — only venues in the matching Berlin district, one of the 23 pre-2001 districts (e.g. kreuzberg). " +
                "Omitted/blank returns all districts."
    )
    val district: String? = null,
    @field:Parameter(description = "Venue type slug (e.g. club). Repeatable: a venue of any given type matches. An unknown type matches nothing.")
    val type: List<String>? = null,
    @field:Parameter(description = "Genre family slug (e.g. electronic). Repeatable: a venue that mostly programmes any given family matches.")
    val family: List<String>? = null,
    @field:Parameter(description = "Event type, e.g. CONCERT (case-insensitive). Repeatable: a venue that hosts any given type matches.")
    val eventType: List<String>? = null
) {
    fun toFilter(): VenueFilter = VenueFilter.of(q, district, type, family, eventType)
}

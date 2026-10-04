package de.norm.events.venue

import de.norm.events.common.TextSearch
import io.swagger.v3.oas.annotations.Parameter

/**
 * The venue list's filter criteria, bound as a model attribute like `EventFilterParams`. A name that
 * is not here, and not paging, is a `400`; the controller derives its accepted set from this class.
 */
data class VenueFilterParams(
    @field:Parameter(description = "Search on the venue name: ignores case, accents and spaces, and forgives small typos. Omitted/blank returns all venues.")
    val q: String? = null,
    @field:Parameter(
        description =
            "District filter, one of the 23 pre-2001 Berlin districts (e.g. kreuzberg). " +
                "Repeatable: a venue in any given district matches."
    )
    val district: List<String>? = null,
    @field:Parameter(description = "Venue type slug (e.g. club). Repeatable: a venue of any given type matches. An unknown type matches nothing.")
    val type: List<String>? = null,
    @field:Parameter(description = "Genre family slug (e.g. electronic). Repeatable: a venue that mostly programmes any given family matches.")
    val family: List<String>? = null,
    @field:Parameter(description = "Event type, e.g. CONCERT (case-insensitive). Repeatable: a venue that hosts any given type matches.")
    val eventType: List<String>? = null,
    @field:Parameter(
        description =
            "Character tag slug (e.g. queer), as the venue describes itself. Repeatable: only a venue with every given tag matches. " +
                "An unknown tag matches nothing."
    )
    val character: List<String>? = null
) {
    /** Event types are upper-cased, so `party` and `PARTY` share a cache entry. */
    fun toFilter(): VenueFilter =
        VenueFilter(
            query = TextSearch.term(q),
            districts = VenueFilter.normalized(district),
            types = VenueFilter.normalized(type),
            families = VenueFilter.normalized(family),
            eventTypes = VenueFilter.normalized(eventType?.map { it.uppercase() }),
            characters = VenueFilter.normalized(character)
        )
}

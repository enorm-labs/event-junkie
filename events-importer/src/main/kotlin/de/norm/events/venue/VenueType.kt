package de.norm.events.venue

import com.fasterxml.jackson.annotation.JsonValue

/**
 * What kind of place a venue is, curated by hand once per venue (#327).
 *
 * A venue carries one or more: SO36 is a live venue and a club. The [slug] is what the
 * `venue.venue_types` column, the `type=` query parameter and the frontend's translation keys
 * carry; [ordinal] is the display order.
 *
 * In the importer beside [District], for the same reason: the kebab-case wire form needs Jackson,
 * and `events-core` carries none. The BFF filters on the raw column.
 */
enum class VenueType(
    @get:JsonValue val slug: String
) {
    CLUB("club"),
    LIVE_VENUE("live-venue"),
    ARENA("arena"),
    BAR("bar"),
    CULTURAL_CENTRE("cultural-centre"),
    THEATRE("theatre"),
    CINEMA("cinema"),
    OPEN_AIR("open-air"),
    GALLERY("gallery");

    companion object {
        /** The type whose [slug] matches, or `null` for a slug the vocabulary does not know. */
        fun fromSlug(slug: String?): VenueType? = entries.firstOrNull { it.slug == slug }
    }
}

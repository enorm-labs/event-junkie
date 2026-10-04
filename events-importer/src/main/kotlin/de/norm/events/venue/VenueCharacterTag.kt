package de.norm.events.venue

import com.fasterxml.jackson.annotation.JsonValue

/**
 * What a venue says about itself, beyond its [VenueType] and its programme: its kind of space, its door and its access (#2379).
 *
 * A tag is set only where the venue's own page states it, and the row keeps that page's URL. The
 * [slug] is what `venue_character_tag.tag`, the BFF's `character=` parameter and the frontend's
 * translation keys carry; [ordinal] is the display order.
 *
 * A venue gets [DRESS_CODE] or [FETISH_DRESS_CODE], never both: a stated fetish code is the more specific fact.
 *
 * No `open-air`: [VenueType.OPEN_AIR] already marks a venue with an open-air floor, and filters by it.
 */
enum class VenueCharacterTag(
    @get:JsonValue val slug: String
) {
    QUEER("queer"),
    SEX_POSITIVE("sex-positive"),
    DIY_COLLECTIVE("diy-collective"),
    AWARENESS_TEAM("awareness-team"),
    SAFER_SPACE_POLICY("safer-space-policy"),
    QUIET_ROOM("quiet-room"),
    ALL_GENDER_TOILETS("all-gender-toilets"),

    /** Free drinking water at the bar or a water station. A tap in the toilets does not count: every venue has one. */
    FREE_WATER("free-water"),

    /** No smoking on the floors. A separate smoking room is allowed. */
    SMOKE_FREE("smoke-free"),

    /** A stated dress code that is not a fetish code. A preference ("well-groomed appreciated") is not a code. */
    DRESS_CODE("dress-code"),
    FETISH_DRESS_CODE("fetish-dress-code"),

    /** No photos inside, phones included. A ban on cameras that allows phones is not this. */
    NO_PHOTO_POLICY("no-photo-policy"),

    /** Only cash at the bar and the door. Cash for tickets alone is not this. */
    CASH_ONLY("cash-only"),

    /** Step-free access to the venue as the visitor uses it. Partial access gets no tag. */
    WHEELCHAIR_ACCESSIBLE("wheelchair-accessible");

    companion object {
        /** The tag whose [slug] matches, or `null` for a slug the vocabulary does not know. */
        fun fromSlug(slug: String?): VenueCharacterTag? = entries.firstOrNull { it.slug == slug }
    }
}

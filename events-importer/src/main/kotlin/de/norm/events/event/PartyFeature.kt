package de.norm.events.event

import com.fasterxml.jackson.annotation.JsonValue

/**
 * What kind of night an event is, as its own text states it (#2631). The night's counterpart of the
 * venue's [de.norm.events.venue.VenueCharacterTag], and it shares that vocabulary's slug where both
 * have the fact. Each night stands alone: a venue tag never fills a night that says nothing.
 *
 * The [slug] is what `event_feature.feature`, the BFF's `feature=` parameter and the frontend's
 * translation keys carry; [ordinal] is the display order. [PartyFeatureRules] sets them.
 *
 * Not here: audience labels that are our opinion about visitors (#327, #2379), and an age limit,
 * which is a number and needs its own field.
 */
enum class PartyFeature(
    @get:JsonValue val slug: String
) {
    /** Only FLINTA* people get in. A FLINTA* lineup at an open door is not this. */
    FLINTA_ONLY("flinta-only"),

    /** The night calls itself a queer, gay or LGBTQ party. A queer artist on the bill is not this. */
    QUEER("queer"),
    SEX_POSITIVE("sex-positive"),

    /** A stated dress code that is not a fetish code. "Dress to impress" is a wish, not a code. */
    DRESS_CODE("dress-code"),

    /** A fetish dress code. A night gets this or [DRESS_CODE], never both: it is the more specific fact. */
    FETISH_DRESS_CODE("fetish-dress-code"),

    /** No photos inside, phones included. A ban on cameras that allows phones is not this. */
    NO_PHOTO_POLICY("no-photo-policy"),

    /** The night states no end ("open end", "Ende offen"). An afterhour that says nothing more is uncertain. */
    OPEN_END("open-end"),

    /** The night calls itself a day party or day rave. A daytime start alone is #2720's time-of-day filter. */
    DAY_PARTY("day-party");

    companion object {
        /** The feature whose [slug] matches, or `null` for a slug the vocabulary does not know. */
        fun fromSlug(slug: String?): PartyFeature? = entries.firstOrNull { it.slug == slug }
    }
}

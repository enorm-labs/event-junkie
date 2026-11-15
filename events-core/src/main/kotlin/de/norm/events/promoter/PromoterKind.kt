package de.norm.events.promoter

import com.fasterxml.jackson.annotation.JsonValue

/**
 * What a promoter row is (#5080). On an event every kind plays the same part, it presents the night; the kind says who
 * the presenter is. A person decides it in `docs/promoters/REVIEWED.tsv`, and a row nobody decided has none.
 *
 * The [slug] is what `promoter.kind`, the APIs and the frontend's translation keys carry.
 */
enum class PromoterKind(
    @get:JsonValue val slug: String
) {
    /** A business that books other people's acts: an agency, a concert promoter. */
    PROMOTER("promoter"),

    /** A group of DJs or artists who host their own nights, at one venue or many. */
    COLLECTIVE("collective"),

    /** A record label, presenting a showcase of its roster. */
    LABEL("label"),

    /** The name of a recurring night rather than of the people behind it. */
    PARTY("party"),

    /** A magazine, radio station or other outlet credited as a media partner. */
    MEDIA("media"),

    /** An act credited as its own presenter. */
    ARTIST("artist"),

    /** A brand that sponsors the night. */
    SPONSOR("sponsor"),

    /** A venue credited as the presenter. */
    VENUE("venue");

    companion object {
        /** The kind whose [slug] matches, or `null` for a slug the vocabulary does not know. */
        fun fromSlug(slug: String?): PromoterKind? = entries.firstOrNull { it.slug == slug }
    }
}

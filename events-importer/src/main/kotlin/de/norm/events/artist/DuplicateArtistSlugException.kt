package de.norm.events.artist

/**
 * Thrown when attempting to create or update an artist with a name that generates
 * a slug already used by another artist, or kept as the former slug of one ([formerSlugOf], #3289).
 * Slugs are derived deterministically from names via [de.norm.events.slug.SlugGenerator], so different
 * names can collide (e.g. "Motörhead" and "Motorhead" both produce "motorhead").
 */
class DuplicateArtistSlugException(
    name: String,
    slug: String,
    formerSlugOf: Long? = null
) : RuntimeException(
        if (formerSlugOf == null) {
            "An artist with slug '$slug' already exists (generated from name '$name')"
        } else {
            "Slug '$slug' (generated from name '$name') is the former slug of artist $formerSlugOf, which the import still bills under it"
        }
    )

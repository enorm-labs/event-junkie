package de.norm.events.festival

/** Two names can fold to one slug, as "Fête de la Musique" and "Fete de la Musique" do. */
class DuplicateFestivalSlugException(
    name: String,
    slug: String
) : RuntimeException("A festival with slug '$slug' already exists (generated from name '$name')")

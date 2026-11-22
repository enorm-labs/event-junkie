package de.norm.events.event

/**
 * The house genre of each source that declares one, keyed by `event_source.source_type`: the genre text the import stores on a
 * music event that names none. The `scraper` module owns the declarations and depends on this one, so this side holds only the shape.
 */
fun interface HouseGenres {
    fun bySourceType(): Map<String, String>
}

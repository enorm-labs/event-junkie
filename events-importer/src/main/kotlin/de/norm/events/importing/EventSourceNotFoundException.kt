package de.norm.events.importing

/**
 * Exception thrown when an event source with the given slug is not found.
 */
class EventSourceNotFoundException(
    slug: String
) : RuntimeException("Event source not found: $slug")

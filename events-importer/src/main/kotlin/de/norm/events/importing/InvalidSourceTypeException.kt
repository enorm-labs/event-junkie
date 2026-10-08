package de.norm.events.importing

import de.norm.events.scraper.EventSource

/**
 * Exception thrown when a client provides an unrecognized [EventSource] type value
 * in an event source create request. This is a client input error (400 BAD REQUEST)
 * because the provided `sourceType` does not match any known [EventSource] enum constant.
 */
class InvalidSourceTypeException(
    sourceType: String
) : RuntimeException(
        "Unknown source type '$sourceType'. Valid values: ${EventSource.entries.joinToString { it.name }}"
    )

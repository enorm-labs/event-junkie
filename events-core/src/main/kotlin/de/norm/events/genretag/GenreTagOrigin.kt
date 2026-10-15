package de.norm.events.genretag

/** Where an event's genre tag comes from (ADR-047). Only an event with no [SOURCE] tag gets a derived one. */
enum class GenreTagOrigin {
    /** The event's source published it. */
    SOURCE,

    /** The source tags of the billed acts' other events. */
    ARTIST,

    /** A genre word in the event's description. */
    DESCRIPTION
}

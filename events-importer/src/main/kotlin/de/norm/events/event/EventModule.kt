package de.norm.events.event

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the event module's allowed dependencies.
 *
 * Events are the central aggregate linking venues, artists, promoters, and genre tags.
 * The event module needs access to artist, venue, promoter, and genretag modules to:
 * - Validate that referenced entities exist before persisting associations
 * - Query join tables (event_artist, event_promoter, event_genre_tag) for response assembly
 */
@ApplicationModule(allowedDependencies = ["artist", "venue", "promoter", "slug", "genretag", "common"])
class EventModule

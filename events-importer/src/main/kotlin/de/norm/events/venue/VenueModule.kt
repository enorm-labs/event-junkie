package de.norm.events.venue

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the venue module as self-contained with no
 * dependencies on other application modules.
 *
 * Venues are a standalone entity — they do not reference artists, events,
 * or promoters. Other modules (e.g. event) depend on venue, not the other
 * way around.
 */
@ApplicationModule(allowedDependencies = ["slug", "common"])
class VenueModule

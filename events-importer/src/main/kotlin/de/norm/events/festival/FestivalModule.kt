package de.norm.events.festival

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for festivals: one date range across many venues (#3272). Standalone, like
 * promoters: other modules may depend on festival, never the other way around.
 */
@ApplicationModule(allowedDependencies = ["slug", "common"])
class FestivalModule

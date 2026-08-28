package de.norm.events.event

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the event read module. Events embed venue, artist, promoter, and genre
 * tag summaries, so it depends on those modules plus the shared `common` module.
 *
 * It also depends on `sourcelicence`, which decides whether a description or an image may be shown
 * at all (#283). That edge is one-way: this module asks and applies the answer itself.
 */
@ApplicationModule(allowedDependencies = ["common", "venue", "artist", "promoter", "genretag", "sourcelicence"])
class EventModule

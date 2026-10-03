package de.norm.events.search

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the header search (#2514). It asks the four read modules for their own name
 * search and only groups the answers; no module depends on it.
 */
@ApplicationModule(allowedDependencies = ["common", "event", "venue", "artist", "promoter"])
class SearchModule

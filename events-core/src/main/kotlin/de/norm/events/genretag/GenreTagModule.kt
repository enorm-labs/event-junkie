package de.norm.events.genretag

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the genre tag module as self-contained with no
 * dependencies on other application modules.
 */
@ApplicationModule(allowedDependencies = [])
class GenreTagModule

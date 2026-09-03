package de.norm.events.genretag

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the genre tag module's allowed dependencies.
 *
 * Genre tags are standalone entities with no dependencies on other modules.
 * The slug module is used for generating URL-friendly slugs from genre names.
 */
@ApplicationModule(allowedDependencies = ["slug", "common"])
class GenreTagModule

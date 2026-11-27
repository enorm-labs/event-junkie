package de.norm.events.slug

import org.springframework.modulith.ApplicationModule

/**
 * Module providing URL-friendly slug generation as a shared utility.
 *
 * It wraps the external [com.github.slugify.Slugify] library behind a singleton ([SlugGenerator])
 * so all feature modules share a single instance. Its one dependency is `common`, for the accent
 * stripping every name comparison shares.
 */
@ApplicationModule(allowedDependencies = ["common"])
class SlugModule

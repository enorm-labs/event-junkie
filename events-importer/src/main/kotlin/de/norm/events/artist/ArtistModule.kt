package de.norm.events.artist

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the artist module as self-contained with no
 * dependencies on other application modules.
 *
 * Artists are a standalone entity — they do not reference venues, events,
 * or promoters. Other modules (e.g. event) depend on artist, not the other
 * way around.
 */
@ApplicationModule(allowedDependencies = ["slug", "common"])
class ArtistModule

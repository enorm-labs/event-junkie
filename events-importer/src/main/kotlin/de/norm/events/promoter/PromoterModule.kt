package de.norm.events.promoter

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata declaring the promoter module as self-contained with no
 * dependencies on other application modules.
 *
 * Promoters are a standalone entity — they do not reference artists, events,
 * or venues. Other modules (e.g. event) depend on promoter, not the other
 * way around.
 */
@ApplicationModule(allowedDependencies = ["slug", "common"])
class PromoterModule

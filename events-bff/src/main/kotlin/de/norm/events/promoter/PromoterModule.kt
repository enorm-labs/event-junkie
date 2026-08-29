package de.norm.events.promoter

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the promoter read module.

 * Depends on the shared `common` module, and on `image`, which decides whether the image URL it
 * hands out is the venue's or ours (ADR-019, #833). That edge is one-way: this module asks and
 * applies the answer itself.
 */
@ApplicationModule(allowedDependencies = ["common", "image"])
class PromoterModule

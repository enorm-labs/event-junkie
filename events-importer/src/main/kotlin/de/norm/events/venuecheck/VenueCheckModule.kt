package de.norm.events.venuecheck

import org.springframework.modulith.ApplicationModule

/**
 * The monthly site check of venues without an importer (#2812).
 *
 * It depends on `scraper` for the throttled, `robots.txt`-checking [de.norm.events.scraper.SCRAPER_WEB_CLIENT],
 * the same edge [de.norm.events.image.ImageModule] takes. `venue` and `importing` are absent on purpose: the
 * candidates and the review list are read with raw SQL in [VenueSiteCheckStore], because all this needs is a few
 * columns.
 */
@ApplicationModule(allowedDependencies = ["scraper"])
class VenueCheckModule

package de.norm.events.image

import org.springframework.modulith.ApplicationModule

/**
 * Caching of venue images on our own origin (ADR-019).
 *
 * **The edge points this way on purpose.** This module reads event image URLs and calls the
 * scraper's throttled [de.norm.events.scraper.SCRAPER_WEB_CLIENT], so it depends on `scraper`.
 * Nothing in `scraper` refers back to it — the fetch is driven by this module's own schedule rather
 * than from inside the import pipeline. Reversing that would be a cycle, and it would also put
 * image fetching inside the transaction that writes events.
 *
 * `event` is deliberately absent. The URLs are read with raw SQL in [CachedImageRepository] rather
 * than through the event module's types, because all this needs is a column.
 */
@ApplicationModule(allowedDependencies = ["scraper"])
class ImageModule

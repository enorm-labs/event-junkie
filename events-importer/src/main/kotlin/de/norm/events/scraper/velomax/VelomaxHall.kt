package de.norm.events.scraper.velomax

import de.norm.events.scraper.EventSource

/**
 * The three halls sharing the one Velomax listing page.
 *
 * `velomax.de/events` interleaves all three chronologically. Unlike Club der Visionäre's
 * colour-coded rooms, the hall is stated three times — a CSS class on the entry, a `.location`
 * label, and the detail link's domain — so [cssClass] is a durable filter, not a last resort.
 * Each hall is its own import source with its own venue and `sourceId` prefix, all served by
 * [VelomaxOverviewPageScraper] filtering on that class.
 */
enum class VelomaxHall(
    /** The CSS class the listing puts on an entry belonging to this hall. */
    val cssClass: String,
    /** The import source this hall's events are attributed to. */
    val eventSource: EventSource
) {
    /** The Prenzlauer Berg arena — the largest, and the one with most of the sport. */
    MAX_SCHMELING_HALLE("msh", EventSource.MAX_SCHMELING_HALLE),

    /** The Landsberger Allee arena. */
    VELODROM("velodrom", EventSource.VELODROM),

    /** The smaller hall configured inside the Velodrom, listed under its own `UFO` location. */
    UFO_IM_VELODROM("ufo", EventSource.UFO_IM_VELODROM)
}

package de.norm.events.scraper.kesselhaus

import de.norm.events.scraper.EventSource

/**
 * The two Kulturbrauerei stages whose events the Kesselhaus calendar lists, each its own source.
 *
 * The calendar also lists the Soda Biergarten, Club23 and the Kulturbrauerei courtyard, which are
 * other venues. Each event names its room as a `/venues//<slug>` reference, the CMS's own id.
 */
enum class KesselhausRoom(
    /** The `venue` slugs this room's events carry; a night across both rooms counts for the larger one. */
    val venueSlugs: Set<String>,
    val eventSource: EventSource,
    val venueName: String
) {
    KESSELHAUS(setOf("kesselhaus", "kesselhaus-maschinenhaus"), EventSource.KESSELHAUS, "Kesselhaus"),
    MASCHINENHAUS(setOf("maschinenhaus"), EventSource.MASCHINENHAUS, "Maschinenhaus")
}

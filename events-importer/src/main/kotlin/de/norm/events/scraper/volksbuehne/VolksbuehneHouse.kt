package de.norm.events.scraper.volksbuehne

import de.norm.events.scraper.EventSource

/**
 * The two Volksbühne houses, each its own venue and source: Rosa-Luxemburg-Platz and the Prater on
 * Kastanienallee stand about 1.5 km apart. A row names its stage in `.activity__stage`; a stage
 * other than the main one is the event's room. A guest performance elsewhere (`Ludwigshafen`)
 * matches no house and drops.
 */
enum class VolksbuehneHouse(
    val eventSource: EventSource,
    val venueName: String,
    /** The stages this house holds, each mapped to the room it is, or null for the main stage. */
    val stages: Map<String, String?>
) {
    ROSA_LUXEMBURG_PLATZ(
        EventSource.VOLKSBUEHNE,
        "Volksbühne",
        mapOf(
            "Große Bühne" to null,
            "Vorbühne" to "Vorbühne",
            "Roter Salon" to "Roter Salon",
            "Grüner Salon" to "Grüner Salon",
            "3. Stock" to "3. Stock",
            "Sternfoyer" to "Sternfoyer",
            "Parkettcafé" to "Parkettcafé",
            "Treffpunkt Kassenhalle" to "Kassenhalle"
        )
    ),
    PRATER(
        EventSource.VOLKSBUEHNE_PRATER,
        "Volksbühne Prater",
        mapOf("Prater" to null, "Prater-Foyer" to "Foyer", "Prater-Probebühne" to "Probebühne")
    );

    companion object {
        /** Whether [stage] belongs to either house; a row on any other is a guest performance elsewhere. */
        fun isKnownStage(stage: String): Boolean = entries.any { stage in it.stages }
    }
}

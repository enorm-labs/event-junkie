package de.norm.events.scraper.hau

import de.norm.events.scraper.EventSource

/**
 * The three HAU Hebbel am Ufer houses, each its own venue and source: they stand at three
 * addresses. A row names its stage in `data-venue`; a stage inside a house is the event's room.
 * HAU4, the digital stage, is no house, so its rows match none and drop.
 */
enum class HauHouse(
    val eventSource: EventSource,
    val venueName: String,
    /** The `data-venue` stages this house holds, each mapped to the room it is, or null for the main stage. */
    val stages: Map<String, String?>
) {
    HAU1(EventSource.HAU1, "HAU1", mapOf("HAU1" to null)),
    HAU2(EventSource.HAU2, "HAU2", mapOf("HAU2" to null, "WAU" to "WAU")),
    HAU3(EventSource.HAU3, "HAU3", mapOf("HAU3" to null, "HAU3 Houseclub" to "Houseclub"))
}

package de.norm.events.scraper

import java.time.LocalDate

/**
 * A source whose site refuses the importer, a block we accepted rather than got around, checked by
 * hand on [since] (#2201).
 *
 * The entry tags `importer.source.last_success` with `known_blocked="true"`, which moves the source
 * from `ej-importer-stale` (36 h) to `ej-importer-stale-blocked` (8 days). A marked source must
 * still succeed some of the time, as Sisyphos does on weekends through sisy.fan. Delete the entry
 * when the block is lifted.
 */
data class KnownBlockedSource(
    val since: LocalDate,
    val reason: String,
    val issue: Int
)

/** The sources known to be blocked, keyed by the `event_source` slug the gauges carry. */
val KNOWN_BLOCKED_SOURCES: Map<String, KnownBlockedSource> =
    mapOf(
        "sisyphos" to
            KnownBlockedSource(
                LocalDate.of(2026, 9, 30),
                "the Shopify shop's bot protection answers the importer 429 from a hosting address; " +
                    "the weekends still import from sisy.fan (ADR-036)",
                issue = 2199
            )
    )

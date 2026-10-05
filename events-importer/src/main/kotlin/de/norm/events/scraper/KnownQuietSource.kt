package de.norm.events.scraper

import java.time.LocalDate

/**
 * A source the venue itself has left at zero future events, checked by hand on [since] (#1498).
 *
 * The entry keeps `ej-source-quiet` from naming the source and lets the dashboard mark it as known
 * rather than broken. It is not a limitation: `AcceptedLimitations.kt` is per aspect, and
 * "publishes nothing" is not an aspect. Delete the entry when the venue publishes again — the gauge
 * drops to zero on its own, and a stale entry is only a source nobody watches.
 *
 * An entry is for a silence longer than the rule's 30 days. A venue that posts one week at a time
 * is empty for a few days between posts and never reaches the rule, so it gets no entry (#2329).
 */
data class KnownQuietSource(
    val since: LocalDate,
    val reason: String
)

/** The sources known to be quiet, keyed by the `event_source` slug the gauges carry. */
val KNOWN_QUIET_SOURCES: Map<String, KnownQuietSource> =
    mapOf(
        "gart-n" to
            KnownQuietSource(
                LocalDate.of(2026, 9, 30),
                "the season closed on 27 September, and the venue has posted nothing since (#2085)"
            )
    )

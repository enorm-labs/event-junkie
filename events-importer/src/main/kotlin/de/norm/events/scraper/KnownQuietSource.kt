package de.norm.events.scraper

import java.time.LocalDate

/**
 * A source the venue itself has left at zero future events, checked by hand on [since] (#1498).
 *
 * The entry keeps `ej-source-quiet` from naming the source and lets the dashboard mark it as known
 * rather than broken. It is not a limitation: `AcceptedLimitations.kt` is per aspect, and
 * "publishes nothing" is not an aspect. Delete the entry when the venue publishes again — the gauge
 * drops to zero on its own, and a stale entry is only a source nobody watches.
 */
data class KnownQuietSource(
    val since: LocalDate,
    val reason: String
)

/** The sources known to be quiet, keyed by the `event_source` slug the gauges carry. */
val KNOWN_QUIET_SOURCES: Map<String, KnownQuietSource> =
    mapOf(
        "amt" to KnownQuietSource(LocalDate.of(2026, 9, 16), "the events page links the months July and August only"),
        "golden-gate" to
            KnownQuietSource(LocalDate.of(2026, 9, 16), "the programme stops at 12 September and the footer still says 2025"),
        "arkaoda" to KnownQuietSource(LocalDate.of(2026, 9, 16), "the programme page is the bare template with no entry")
    )

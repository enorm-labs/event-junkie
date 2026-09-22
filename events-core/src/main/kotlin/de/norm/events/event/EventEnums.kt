package de.norm.events.event

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Categorizes events by their type/kind as observed on venue websites.
 *
 * Derived from the "kind" field shown on sites like Astra Berlin
 * (Concert, Festival, Party, etc.) and category labels on Badehaus Berlin
 * (Concert, Party, Quiz, etc.).
 */
enum class EventType {
    CONCERT,
    FESTIVAL,
    PARTY,
    QUIZ,
    CLUB_NIGHT,
    SHOW,
    SCREENING,
    EXHIBITION,
    READING,
    OTHER;

    companion object {
        /** Safely parses [value] to an [EventType], falling back to [OTHER] for unrecognized values. Case-insensitive, trims whitespace. */
        fun parseOrDefault(value: String): EventType =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: OTHER.also { logger.warn { "Unknown EventType '$value', defaulting to OTHER" } }
    }
}

/**
 * Tracks the scheduling status of an event.
 *
 * Berlin venues frequently relocate ("VERLEGT") or cancel events;
 * this enum captures those states for display and filtering.
 */
enum class EventStatus {
    /** Event is taking place as originally planned. */
    SCHEDULED,

    /** Event has been moved to a different venue or date (German: "VERLEGT"). */
    RELOCATED,

    /** Event has been cancelled and will not take place. */
    CANCELLED,

    /** Event has been postponed to an unannounced future date. */
    POSTPONED;

    companion object {
        /** Safely parses [value] to an [EventStatus], falling back to [SCHEDULED] for unrecognized values. Case-insensitive, trims whitespace. */
        fun parseOrDefault(value: String): EventStatus =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: SCHEDULED.also { logger.warn { "Unknown EventStatus '$value', defaulting to SCHEDULED" } }
    }
}

/**
 * Describes an artist's role/position in an event lineup.
 */
enum class ArtistRole {
    /** Main act / top-billed performer */
    HEADLINER,

    /** Supporting act listed below the headliner */
    SUPPORT,

    /** DJ set, typically at aftershow parties or festivals */
    DJ;

    companion object {
        /** Safely parses [value] to an [ArtistRole], falling back to [HEADLINER] for unrecognized values. Case-insensitive, trims whitespace. */
        fun parseOrDefault(value: String): ArtistRole =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: HEADLINER.also { logger.warn { "Unknown ArtistRole '$value', defaulting to HEADLINER" } }
    }
}

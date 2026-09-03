package de.norm.events.scraper.berghain

import de.norm.events.event.EventType

/**
 * Maps a Berghain floor label to its signature music genre.
 *
 * The site exposes **no** structured genre field — genre words appear only woven
 * into the editorial description prose, and always attached to an individual
 * artist rather than the night as a whole (e.g. one act "modernen Techno", the
 * next an "Acid-House-Jam"). So there is nothing to reliably *extract*. What each
 * room *does* have is a strong, settled programming identity, which makes the
 * floor a useful genre **default** for filtering:
 * - **Berghain** (the main hall) → Techno
 * - **Panorama Bar** → House
 * - **Säule** (the small experimental room) → Experimental
 *
 * This is a curated stereotype, not ground truth: a given line-up can diverge
 * from its room's usual sound (an Acid House bill booked into the Berghain hall
 * does happen). Floors without one settled genre — the Halle event hall and the
 * Kantine am Berghain concert hall, both of which host varied bills — contribute
 * nothing and yield `null`.
 *
 * The `Kantine` check comes first because its label ("Kantine am Berghain")
 * contains the substring "Berghain" and must not be mis-mapped to Techno.
 */
private fun floorToGenre(floor: String): String? {
    val label = floor.trim()
    return when {
        label.contains("Kantine", ignoreCase = true) -> null
        label.contains("Panorama Bar", ignoreCase = true) -> "House"
        label.contains("Säule", ignoreCase = true) -> "Experimental"
        label.contains("Berghain", ignoreCase = true) -> "Techno"
        else -> null
    }
}

/**
 * Derives an event's genre from the floor(s) it runs on, or `null` when no floor
 * maps to a settled genre. Distinct floor genres are joined in listing order, so a
 * night split across the main hall and Panorama Bar reads "Techno, House".
 *
 * @see floorToGenre for the per-floor mapping and its stereotype caveat.
 */
fun floorsToGenre(floors: List<String>): String? =
    floors
        .mapNotNull(::floorToGenre)
        .distinct()
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

/**
 * Types an event from its floor label(s): the Kantine am Berghain concert hall lists live
 * [CONCERT][EventType.CONCERT]s, while the Berghain building floors (Berghain, Panorama Bar,
 * Säule, Halle) host club [PARTY][EventType.PARTY] nights.
 *
 * Returns `null` when no floor is given, letting the persistence boundary apply the `OTHER`
 * default.
 */
internal fun floorsToEventType(floors: List<String>): String? =
    when {
        floors.any { it.contains(KANTINE_MARKER, ignoreCase = true) } -> EventType.CONCERT.name
        floors.isNotEmpty() -> EventType.PARTY.name
        else -> null
    }

/** Floor label identifying the adjacent concert hall (vs. the Berghain building's club floors). */
private const val KANTINE_MARKER = "Kantine"

/** Doors time in the date line, e.g. "tür 19:00" (German "Tür" = door). */
internal val BERGHAIN_DOORS_PATTERN = Regex("""tür\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

/** Show start time in the date line, e.g. "beginn 21:00". */
internal val BERGHAIN_START_PATTERN = Regex("""beginn\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

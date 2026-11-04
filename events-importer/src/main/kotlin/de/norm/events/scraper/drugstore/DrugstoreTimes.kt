package de.norm.events.scraper.drugstore

import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.labelledClockPattern
import de.norm.events.scraper.radar.RadarGroupImporter.Times
import java.time.LocalTime

/**
 * A night's times, from the `Doors:` and `Start:` labels of a radar description. Radar's one time is
 * the doors on the nights that label both (`Doors: 7pm`, `Start: 8:30pm`).
 *
 * [radarTime] stays the start, without doors, on a text with no `Doors:`, and on a text whose
 * labels cannot be read for this row. Those are several nights in one text, and a start before the
 * doors: a row holds one date, so `Doors: 11pm`, `Start: 1am` has no correct stored form.
 */
internal fun parseDrugstoreTimes(
    description: String?,
    radarTime: LocalTime
): Times {
    val unread = Times(start = radarTime)
    // radar writes a non-breaking space where a space goes, which the clock pattern's `\s` does not match.
    val text = description?.replace('\u00A0', ' ') ?: return unread
    if (TIME_LABELS.any { labelledClockPattern(it).findAll(text).count() > 1 }) return unread
    val doors = labelledClock(text, DOORS_LABEL) ?: return unread
    val start = labelledClock(text, START_LABEL) ?: radarTime
    return when {
        doors > start -> unread
        doors == start -> Times(start = start)
        else -> Times(start = start, doors = doors)
    }
}

private const val DOORS_LABEL = "doors:"

private const val START_LABEL = "start:"

private val TIME_LABELS = listOf(DOORS_LABEL, START_LABEL)

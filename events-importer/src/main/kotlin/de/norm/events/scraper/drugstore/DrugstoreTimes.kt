package de.norm.events.scraper.drugstore

import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.labelledClockPattern
import de.norm.events.scraper.radar.RadarGroupImporter.Times
import java.time.LocalTime

/**
 * A night's times, from the labels of a radar description. Radar's one time is the doors on the
 * nights that label both (`Doors: 7pm`, `Start: 8:30pm`, or `Doors 18 | Start 19`). `Beginn: 19 Uhr`
 * alone gives the start, and says nothing about what radar's time is, so the row has no doors.
 *
 * [radarTime] stays the start, without doors, on a text with no label, and on a text whose labels
 * cannot be read for this row. Those are several nights in one text, a start before the doors, and
 * a `Beginn:` before radar's time: a row holds one date, so `Doors: 11pm`, `Start: 1am` has no
 * correct stored form.
 */
internal fun parseDrugstoreTimes(
    description: String?,
    radarTime: LocalTime
): Times {
    val unread = Times(start = radarTime)
    // radar writes a non-breaking space where a space goes, which the clock pattern's `\s` does not match.
    val text = description?.replace('\u00A0', ' ') ?: return unread
    if (TIME_PATTERNS.any { it.findAll(text).count() > 1 }) return unread
    val bareHours = BARE_HOURS.find(text)?.destructured?.let { (doors, start) -> hourClock(doors) to hourClock(start) }
    val doors = bareHours?.first ?: labelledClock(text, DOORS_LABEL)
    val start = bareHours?.second ?: labelledClock(text, START_LABELS_WITH_COLON)
    return when {
        doors != null -> orderedTimes(doors, start ?: radarTime) ?: unread
        else -> labelledClock(text, BEGINN_LABEL)?.takeIf { it > radarTime }?.let { Times(start = it) } ?: unread
    }
}

private fun orderedTimes(
    doors: LocalTime,
    start: LocalTime
): Times? =
    when {
        doors > start -> null
        doors == start -> Times(start = start)
        else -> Times(start = start, doors = doors)
    }

private fun hourClock(hour: String): LocalTime = LocalTime.of(hour.toInt(), 0)

private const val DOORS_LABEL = "doors:"

private const val BEGINN_LABEL = "beginn:"

private const val START_LABELS_WITH_COLON = "start:|$BEGINN_LABEL"

/** Only the hours that cannot be a 12-hour clock: `Doors 6 | Start 7` may be morning or evening. */
private const val UNAMBIGUOUS_HOUR = "(1[3-9]|2[0-3])"

private val BARE_HOURS =
    Regex("""(?<!\p{L})doors\s+$UNAMBIGUOUS_HOUR\s*\|\s*start\s+$UNAMBIGUOUS_HOUR(?!\d|[:.,]\d)""", RegexOption.IGNORE_CASE)

private val TIME_PATTERNS = listOf(labelledClockPattern(DOORS_LABEL), labelledClockPattern(START_LABELS_WITH_COLON), BARE_HOURS)

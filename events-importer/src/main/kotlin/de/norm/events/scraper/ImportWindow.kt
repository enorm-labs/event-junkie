package de.norm.events.scraper

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The time of day a scheduled import may start, read in [zone] (ADR-007 best practice 7, #791).
 *
 * Equal [start] and [end] mean the whole day. A [start] after [end] wraps past midnight. A local
 * time that a DST change skips resolves forward, so a 02:00 opening on the spring change is 03:00.
 */
data class ImportWindow(
    val start: LocalTime,
    val end: LocalTime,
    val zone: ZoneId
) {
    /** True when the window does not restrict the hour at all. */
    val isWholeDay: Boolean get() = start == end

    fun contains(instant: Instant): Boolean {
        if (isWholeDay) return true
        val time = instant.atZone(zone).toLocalTime()
        return if (start < end) time >= start && time < end else time >= start || time < end
    }

    /** The latest opening at or before [instant]: today's when the start has passed, yesterday's otherwise. */
    fun lastOpening(instant: Instant): Instant {
        val date = instant.atZone(zone).toLocalDate()
        val today = ZonedDateTime.of(date, start, zone).toInstant()
        return if (today > instant) ZonedDateTime.of(date.minusDays(1), start, zone).toInstant() else today
    }

    override fun toString(): String = "$start–$end $zone"
}

/**
 * The global import window, `app.scheduling.import-window`. A source's own `import_window_start`
 * and `import_window_end` replace [start] and [end]; [zone] is always this one.
 *
 * Strings rather than [LocalTime], so `"02:00"` parses the same whatever the JVM locale.
 */
@ConfigurationProperties(prefix = "app.scheduling.import-window")
data class ImportWindowProperties(
    val start: String = DEFAULT_START,
    val end: String = DEFAULT_END,
    val zone: String = DEFAULT_ZONE
) {
    /** Parsed once, so a malformed value fails the context at startup rather than the first tick. */
    val window: ImportWindow = ImportWindow(LocalTime.parse(start), LocalTime.parse(end), ZoneId.of(zone))

    companion object {
        const val DEFAULT_START = "02:00"
        const val DEFAULT_END = "06:00"
        const val DEFAULT_ZONE = "Europe/Berlin"
    }
}

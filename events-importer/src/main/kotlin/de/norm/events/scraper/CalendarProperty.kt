package de.norm.events.scraper

import de.norm.events.common.BERLIN
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One content line of an iCalendar component: the raw [value] and its parameters, upper-cased by name. */
internal data class CalendarProperty(
    val value: String,
    val params: Map<String, String> = emptyMap()
) {
    /** The [value] as RFC 5545 TEXT: `\n` a line break, `\,` `\;` `\\` the character itself, and `\"` too, which kalender.digital writes. */
    val text: String get() = TEXT_ESCAPE.replace(value) { if (it.groupValues[1] in "nN") "\n" else it.groupValues[1] }

    private companion object {
        val TEXT_ESCAPE = Regex("""\\([nN,;\\"])""")
    }
}

/** A `DTSTART` or `DTEND` on the venue's clock: an all-day value has no [time]. */
internal data class SiteTime(
    val date: LocalDate,
    val time: LocalTime?
) : Comparable<SiteTime> {
    /** The moment as one value, midnight for an all-day one. */
    val dateTime: LocalDateTime get() = date.atTime(time ?: LocalTime.MIDNIGHT)

    override fun compareTo(other: SiteTime): Int = compareValuesBy(this, other, { it.date }, { it.time })
}

/**
 * A date or date-time property on the venue's clock. `VALUE=DATE`, or a bare `YYYYMMDD`, is all-day.
 * A UTC time (trailing `Z`) is moved to [zone]; any other time is a `TZID` wall clock, read as printed.
 */
internal fun CalendarProperty.siteTime(zone: ZoneId = BERLIN): SiteTime {
    val raw = value.trim()
    if (params["VALUE"] == "DATE" || raw.length == COMPACT_DATE_LENGTH) return SiteTime(LocalDate.parse(raw, COMPACT_DATE), null)
    val local = LocalDateTime.parse(raw.removeSuffix("Z"), ICAL_LOCAL_DATE_TIME)
    val onSiteClock = if (raw.endsWith('Z')) local.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime() else local
    return SiteTime(onSiteClock.toLocalDate(), onSiteClock.toLocalTime())
}

private const val COMPACT_DATE_LENGTH = 8

/**
 * The `VEVENT` components of an iCalendar body, each as its properties by name; the first of a
 * repeated name wins. Folded lines are unfolded first (RFC 5545 §3.1).
 */
internal fun calendarEvents(calendar: String): List<Map<String, CalendarProperty>> =
    VEVENT
        .findAll(calendar.replace(FOLD, ""))
        .map { block ->
            block.groupValues[1]
                .lines()
                .mapNotNull(::contentLine)
                .distinctBy { (name, _) -> name }
                .toMap()
        }.toList()

/** A `NAME;PARAM=value:VALUE` line split at the first colon outside a quoted parameter value. */
private fun contentLine(line: String): Pair<String, CalendarProperty>? {
    var quoted = false
    val colon =
        line.indices.firstOrNull { i ->
            if (line[i] == '"') quoted = !quoted
            line[i] == ':' && !quoted
        } ?: return null
    val head = line.substring(0, colon).split(';')
    val params =
        head
            .drop(1)
            .mapNotNull { param -> param.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.uppercase() to v.trim('"') } }
            .toMap()
    return head.first().uppercase() to CalendarProperty(line.substring(colon + 1), params)
}

/** One `VEVENT` component's content lines. */
private val VEVENT = Regex("""BEGIN:VEVENT\r?\n(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)

/** A line break followed by the space or tab that marks a continuation line. */
private val FOLD = Regex("""\r?\n[ \t]""")

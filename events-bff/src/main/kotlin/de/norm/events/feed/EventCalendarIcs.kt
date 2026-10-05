package de.norm.events.feed

import de.norm.events.common.Site
import de.norm.events.event.CalendarEvent
import de.norm.events.event.EventStatus
import de.norm.events.event.EventSummaryResponse
import de.norm.events.event.EventType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Renders the calendar subscription as RFC 5545, by the rules `events-frontend/src/lib/addToCalendar.ts`
 * writes one event with (#476): TEXT escaping, 75-octet folding, CRLF, UTC per instant, and the
 * per-type default duration marked as an estimate. Change both or neither. UTC per instant needs
 * no `VTIMEZONE`. An entry carries no text and no image of the venue's, as the RSS feed does not.
 */
object EventCalendarIcs {
    const val CONTENT_TYPE = "text/calendar"

    /** How often a client should fetch again. Imports run daily per source. */
    private const val REFRESH = "PT1H"

    private const val MAX_LINE_OCTETS = 75

    /** Hours an event lasts when the venue stated no end. Mirrors `DEFAULT_DURATION_HOURS` in `addToCalendar.ts`. */
    private val DEFAULT_DURATION_HOURS: Map<EventType, Long> =
        mapOf(
            EventType.CONCERT to 3,
            EventType.FESTIVAL to 8,
            EventType.PARTY to 6,
            EventType.QUIZ to 2,
            EventType.SHOW to 2,
            EventType.COMEDY to 2,
            EventType.SCREENING to 2,
            EventType.EXHIBITION to 3,
            EventType.READING to 2,
            EventType.OTHER to 3
        )
    private const val FALLBACK_DURATION_HOURS = 3L
    private const val SECONDS_PER_HOUR = 3600L

    /** The estimate notes of the event page, `events.detail.share.*` in the SPA's messages. */
    private val START_ESTIMATED =
        mapOf("en" to "The start time is our estimate.", "de" to "Die Startzeit ist unsere Schätzung.")
    private val END_ESTIMATED =
        mapOf(
            "en" to "The venue states no end time, so the calendar entry ends at our estimate.",
            "de" to "Die Location nennt kein Ende, daher endet der Kalendereintrag zu unserer Schätzung."
        )

    /** The status line of the event page, `events.status.*` and `events.movedTo` in the SPA's messages. */
    private val POSTPONED_NOTE =
        mapOf("en" to "Postponed: the venue names no new date yet.", "de" to "Verschoben: Die Location nennt noch keinen neuen Termin.")
    private val MOVED_TO = mapOf("en" to "Moved to {venue}", "de" to "Verlegt: {venue}")
    private val MOVED_FROM = mapOf("en" to "was {venue}", "de" to "vorher {venue}")

    private const val VENUE = "{venue}"

    private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val UID_HOST = Site.URL.substringAfter("://")

    /** The calendar in [locale], one of [Site.LOCALES]: the language of the links and the estimate notes. */
    fun render(
        locale: String,
        events: List<CalendarEvent>
    ): String {
        val lines =
            buildList {
                add("BEGIN:VCALENDAR")
                add("VERSION:2.0")
                add("PRODID:-//Event Junkie//Event Junkie//EN")
                add("CALSCALE:GREGORIAN")
                add("METHOD:PUBLISH")
                add("NAME:Event Junkie")
                add("X-WR-CALNAME:Event Junkie")
                add("REFRESH-INTERVAL;VALUE=DURATION:$REFRESH")
                add("X-PUBLISHED-TTL:$REFRESH")
                events.forEach { addAll(vevent(locale, it)) }
                add("END:VCALENDAR")
            }
        return lines.joinToString("") { foldLine(it) + "\r\n" }
    }

    private fun vevent(
        locale: String,
        event: CalendarEvent
    ): List<String> {
        val summary = event.summary
        val span = spanOf(summary)
        val url = "${Site.URL}/$locale/events/${summary.slug}"
        val description =
            (listOfNotNull(statusNote(locale, summary), summary.subtitle) + estimateNotes(locale, span) + url).joinToString("\n\n")
        // The row's last write, not the moment of the request, so the ETag holds between imports.
        val revised = utcStamp(event.lastModified)
        return buildList {
            add("BEGIN:VEVENT")
            add("UID:${uid(summary.slug)}")
            add("DTSTAMP:$revised")
            add("LAST-MODIFIED:$revised")
            addAll(span.lines())
            add("SUMMARY:${escapeText(summary.title)}")
            location(locale, summary, event.room)?.let { add("LOCATION:${escapeText(it)}") }
            add("DESCRIPTION:${escapeText(description)}")
            add("URL:$url")
            // A postponed night is not off, only unsure; a client strikes out or hides CANCELLED.
            when (summary.status) {
                EventStatus.CANCELLED -> add("STATUS:CANCELLED")
                EventStatus.POSTPONED -> add("STATUS:TENTATIVE")
                else -> Unit
            }
            add("END:VEVENT")
        }
    }

    /** The UID the event page's download uses too, so a client recognises the event from either. Mirrors `eventUid`. */
    fun uid(slug: String): String = "$slug@$UID_HOST"

    /** A span with times, in UTC, or whole days with an exclusive end, as RFC 5545 counts them. */
    private sealed interface Span {
        fun lines(): List<String>

        data class Timed(
            val start: Instant,
            val end: Instant,
            val startEstimated: Boolean,
            val endEstimated: Boolean
        ) : Span {
            override fun lines() = listOf("DTSTART:${utcStamp(start)}", "DTEND:${utcStamp(end)}")
        }

        data class AllDay(
            val startDate: LocalDate,
            val endDateExclusive: LocalDate
        ) : Span {
            override fun lines() = listOf("DTSTART;VALUE=DATE:${DATE_STAMP.format(startDate)}", "DTEND;VALUE=DATE:${DATE_STAMP.format(endDateExclusive)}")
        }
    }

    /**
     * When an event happens, as `eventCalendarSpan` decides it: a stated end is used as stated; a start
     * with no end gets the type's default duration, marked as an estimate. A span of days with no end
     * time is whole days, and so is an event with no time.
     */
    private fun spanOf(event: EventSummaryResponse): Span {
        val stated = event.startTime ?: event.doorsTime
        val start = stated ?: event.assumedStartTime
        val endDate = event.endDate
        val endTime = event.endTime
        if (start == null || (endDate != null && endTime == null)) {
            return Span.AllDay(event.eventDate, (endDate ?: event.eventDate).plusDays(1))
        }
        val startUtc = berlinToUtc(event.eventDate, start)
        val statedEnd =
            (if (endDate != null && endTime != null) berlinToUtc(endDate, endTime) else null)
                ?.takeIf { it.isAfter(startUtc) }
        val hours = DEFAULT_DURATION_HOURS[event.eventType] ?: FALLBACK_DURATION_HOURS
        return Span.Timed(
            startUtc,
            statedEnd ?: startUtc.plusSeconds(hours * SECONDS_PER_HOUR),
            startEstimated = stated == null,
            endEstimated = statedEnd == null
        )
    }

    /** The instant a Berlin wall-clock time names; a time inside the spring-forward gap lands an hour later. */
    private fun berlinToUtc(
        date: LocalDate,
        time: LocalTime
    ): Instant = date.atTime(time).atZone(BERLIN).toInstant()

    private fun estimateNotes(
        locale: String,
        span: Span
    ): List<String> =
        when (span) {
            is Span.AllDay -> {
                emptyList()
            }

            is Span.Timed -> {
                listOfNotNull(
                    START_ESTIMATED.getValue(locale).takeIf { span.startEstimated },
                    END_ESTIMATED.getValue(locale).takeIf { span.endEstimated }
                )
            }
        }

    /**
     * `Venue, Room, Street 1, City`, whatever of it is known. Mirrors `eventLocation`. A relocated event names the
     * house it moved to first, so a calendar does not send the visitor to the old one.
     */
    private fun location(
        locale: String,
        event: EventSummaryResponse,
        room: String?
    ): String? {
        val here = listOfNotNull(event.venue.name, room, event.venue.address, event.venue.city).filter { it.isNotBlank() }
        val movedTo = event.movedTo()
        val parts =
            if (movedTo ==
                null
            ) {
                here
            } else {
                listOf(MOVED_TO.getValue(locale).replace(VENUE, movedTo), MOVED_FROM.getValue(locale).replace(VENUE, event.venue.name))
            }
        return parts.joinToString(", ").ifEmpty { null }
    }

    /** The line the event page shows above its text, for a postponed or relocated event. */
    private fun statusNote(
        locale: String,
        event: EventSummaryResponse
    ): String? =
        when {
            event.status == EventStatus.POSTPONED -> POSTPONED_NOTE.getValue(locale)
            else -> event.movedTo()?.let { MOVED_TO.getValue(locale).replace(VENUE, it) + "." }
        }

    private fun utcStamp(instant: Instant): String = UTC_STAMP.format(instant)

    /** An RFC 5545 TEXT value: backslash, semicolon and comma escaped, every line break as `\n`. */
    fun escapeText(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(Regex("\r\n|\r|\n"), "\\\\n")

    /**
     * Folds a content line at 75 octets, continuing with a leading space (RFC 5545 §3.1). Counts
     * UTF-8 bytes, not characters, and never splits one character, a surrogate pair included.
     */
    fun foldLine(line: String): String {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var octets = 0
        line.codePoints().forEach { codePoint ->
            val char = String(Character.toChars(codePoint))
            val size = char.toByteArray(Charsets.UTF_8).size
            // A continuation line's leading space is one of its 75 octets.
            val limit = if (lines.isEmpty()) MAX_LINE_OCTETS else MAX_LINE_OCTETS - 1
            if (octets + size > limit) {
                lines += current.toString()
                current.clear()
                octets = 0
            }
            current.append(char)
            octets += size
        }
        lines += current.toString()
        return lines.joinToString("\r\n ")
    }
}

/** The house a relocated event moved to, when its note names one. */
private fun EventSummaryResponse.movedTo(): String? = relocatedTo?.takeIf { status == EventStatus.RELOCATED && it.isNotBlank() }

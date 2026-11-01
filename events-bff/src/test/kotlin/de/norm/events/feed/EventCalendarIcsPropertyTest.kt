package de.norm.events.feed

import de.norm.events.event.CalendarEvent
import de.norm.events.event.EventStatus
import de.norm.events.event.EventSummaryResponse
import de.norm.events.event.EventType
import de.norm.events.venue.VenueSummaryResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Properties of the RFC 5545 calendar feed for any text and any times: `EventCalendarIcsTest` pins the examples. */
class EventCalendarIcsPropertyTest {
    @Test
    fun `a folded line keeps every physical line within 75 octets and unfolds to the input`() =
        runTest {
            checkAll(TEXT) { text ->
                val line = "SUMMARY:" + EventCalendarIcs.escapeText(text)
                val folded = EventCalendarIcs.foldLine(line)
                val physical = folded.split("\r\n")
                physical.forEach { it.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual 75 }
                physical.drop(1).forEach { it shouldStartWith " " }
                folded.replace("\r\n ", "") shouldBe line
            }
        }

    @Test
    fun `escaped text has no control character but tab and unescapes to the input as a client reads it`() =
        runTest {
            checkAll(TEXT) { text ->
                val escaped = EventCalendarIcs.escapeText(text)
                escaped.filter { it.isISOControl() && it != '\t' } shouldBe ""
                unescape(escaped) shouldBe text.asTextValue()
            }
        }

    @Test
    fun `a rendered calendar is CRLF lines of at most 75 octets with no control character but tab`() =
        runTest {
            checkAll(CALENDAR) { (locale, events, name) ->
                val ics = EventCalendarIcs.render(locale, events, name)
                ics shouldEndWith "\r\n"
                ics.removeSuffix("\r\n").split("\r\n").forEach { line ->
                    line.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual 75
                    line.filter { it.isISOControl() && it != '\t' } shouldBe ""
                }
            }
        }

    @Test
    fun `every event renders as one VEVENT whose summary unescapes to the title and whose end is after its start`() =
        runTest {
            checkAll(CALENDAR) { (locale, events, name) ->
                val vevents = vevents(EventCalendarIcs.render(locale, events, name))
                vevents shouldHaveSize events.size
                vevents.zip(events).forEach { (properties, event) ->
                    properties.filter { it.startsWith("UID:") } shouldBe listOf("UID:${EventCalendarIcs.uid(event.summary.slug)}")
                    properties.single { it.startsWith("SUMMARY:") }.let { unescape(it.removePrefix("SUMMARY:")) } shouldBe
                        event.summary.title.asTextValue()
                    val start = properties.single { it.startsWith("DTSTART") }.substringAfter(':')
                    val end = properties.single { it.startsWith("DTEND") }.substringAfter(':')
                    (end > start) shouldBe true
                }
            }
        }

    private companion object {
        /** The pieces text is built from: ASCII, two- three- and four-octet characters, a ZWJ sequence, the TEXT specials, every break. */
        val PIECES =
            listOf(
                "a",
                "Z",
                "7",
                " ",
                "ü",
                "ß",
                "€",
                "—",
                "你",
                "🎷",
                "👩‍🎤",
                "\t",
                ";",
                ",",
                "\\",
                "\\n",
                ":",
                "\"",
                "\n",
                "\r\n",
                "\r",
                "\u000B",
                "\u0007",
                "\u0085"
            )

        val TEXT: Arb<String> = Arb.list(Arb.element(PIECES), 0..120).map { it.joinToString("") }

        private val SHORT_TEXT: Arb<String> = Arb.list(Arb.element(PIECES), 0..30).map { it.joinToString("") }

        private val BASE_DATE = LocalDate.of(2026, 1, 1)

        /** A date in 2026–2027, so both DST switches are covered, and a time on the quarter hour or the minute. */
        private val DATE: Arb<LocalDate> = Arb.long(0L..729L).map(BASE_DATE::plusDays)

        private val TIME: Arb<LocalTime> =
            Arb.choice(
                Arb.int(0 until 24 * 60).map { LocalTime.of(it / 60, it % 60) },
                Arb.element(LocalTime.of(2, 30), LocalTime.MIDNIGHT, LocalTime.of(23, 59))
            )

        private data class Times(
            val eventDate: LocalDate,
            val doors: LocalTime?,
            val start: LocalTime?,
            val assumed: LocalTime?,
            val endDate: LocalDate?,
            val endTime: LocalTime?
        )

        /** An end date on or after the event date, as `event_end_after_start` requires. */
        private val TIMES: Arb<Times> =
            Arb.bind(
                DATE,
                TIME.orNull(0.6),
                TIME.orNull(0.3),
                TIME.orNull(0.5),
                Arb.long(0L..3L).orNull(0.6),
                TIME.orNull(0.5)
            ) { date, doors, start, assumed, days, endTime -> Times(date, doors, start, assumed, days?.let(date::plusDays), endTime) }

        private val EVENT: Arb<CalendarEvent> =
            Arb.bind(
                Arb.list(Arb.element(('a'..'z').map(Char::toString) + "-"), 1..120).map { "2026-01-01-" + it.joinToString("") },
                TEXT,
                SHORT_TEXT.orNull(0.5),
                Arb.enum<EventType>(),
                Arb.enum<EventStatus>(),
                SHORT_TEXT.orNull(0.5),
                TIMES,
                Arb.bind(SHORT_TEXT, SHORT_TEXT.orNull(0.3), SHORT_TEXT.orNull(0.5), SHORT_TEXT, ::Location)
            ) { slug, title, subtitle, type, status, relocatedTo, times, location ->
                CalendarEvent(summary(slug, title, subtitle, type, status, relocatedTo, times, location), location.room, Instant.EPOCH)
            }

        private data class Location(
            val venue: String,
            val room: String?,
            val address: String?,
            val city: String
        )

        val CALENDAR: Arb<Triple<String, List<CalendarEvent>, String?>> =
            Arb.bind(Arb.element("en", "de"), Arb.list(EVENT, 0..4), SHORT_TEXT.orNull(0.3)) { locale, events, name ->
                Triple(locale, events, name)
            }

        @Suppress("LongParameterList")
        private fun summary(
            slug: String,
            title: String,
            subtitle: String?,
            type: EventType,
            status: EventStatus,
            relocatedTo: String?,
            times: Times,
            location: Location
        ) = EventSummaryResponse(
            id = 1,
            slug = slug,
            title = title,
            subtitle = subtitle,
            eventType = type,
            status = status,
            relocatedTo = relocatedTo,
            eventDate = times.eventDate,
            doorsTime = times.doors,
            startTime = times.start,
            endDate = times.endDate,
            endTime = times.endTime,
            assumedStartTime = times.assumed,
            imageUrl = null,
            imageSources = emptyList(),
            intrinsicWidth = null,
            intrinsicHeight = null,
            imageWithheld = false,
            pricePresale = null,
            priceBoxOffice = null,
            priceCurrency = "EUR",
            priceNote = null,
            soldOut = false,
            free = false,
            venue =
                VenueSummaryResponse(
                    id = 1,
                    slug = "venue",
                    name = location.venue,
                    city = location.city,
                    address = location.address,
                    district = null,
                    latitude = null,
                    longitude = null,
                    imageUrl = null,
                    imageAttribution = null,
                    imageLicenceId = null,
                    imageSourceUrl = null,
                    imageSources = emptyList(),
                    intrinsicWidth = null,
                    intrinsicHeight = null,
                    capacity = null
                ),
            artistNames = emptyList(),
            genreTags = emptyList()
        )

        /** What a client reads back from [text]: every line break as LF, no other control character but tab. */
        fun String.asTextValue(): String = replace(Regex("\r\n|\r"), "\n").filterNot { it.isISOControl() && it != '\t' && it != '\n' }

        /** RFC 5545 TEXT unescaping: `\n` or `\N` is a line break, any other escaped character stands for itself. */
        fun unescape(text: String): String =
            buildString {
                var index = 0
                while (index < text.length) {
                    val char = text[index]
                    if (char == '\\' && index + 1 < text.length) {
                        val next = text[index + 1]
                        append(if (next == 'n' || next == 'N') '\n' else next)
                        index += 2
                    } else {
                        append(char)
                        index++
                    }
                }
            }

        /** The content lines of each VEVENT, unfolded. */
        fun vevents(ics: String): List<List<String>> {
            val lines = ics.replace("\r\n ", "").removeSuffix("\r\n").split("\r\n")
            return lines.indices
                .filter { lines[it] == "BEGIN:VEVENT" }
                .map { begin -> lines.subList(begin + 1, lines.subList(begin, lines.size).indexOf("END:VEVENT") + begin) }
        }
    }
}

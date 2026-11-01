package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Round trips through [labelledClock]: `TimeParsingExtensionsTest` pins the examples. */
class LabelledClockPropertyTest {
    @Test
    fun `a doors clock reads back behind any doors label, lead and spelling`() =
        runTest {
            checkAll(TIME, Arb.element(DOORS), Arb.element(LEADS), Arb.element(LETTER_CASES)) { time, label, lead, case ->
                spellings(time).forEach { clock -> labelledClock("${case(label)}$lead $clock", DOORS_LABELS) shouldBe time }
            }
        }

    @Test
    fun `a start clock reads back behind any start label, lead and spelling`() =
        runTest {
            checkAll(TIME, Arb.element(STARTS), Arb.element(LEADS), Arb.element(LETTER_CASES)) { time, label, lead, case ->
                spellings(time).forEach { clock -> labelledClock("${case(label)}$lead $clock", START_LABELS) shouldBe time }
            }
        }

    // Venues flatten doors and start onto one line: the label is all that tells them apart.
    @Test
    fun `doors and start on one line each read their own clock, in either order`() =
        runTest {
            checkAll(
                TIME,
                TIME,
                Arb.element(DOORS),
                Arb.element(STARTS),
                Arb.element(" ", " / ", ", ", " · ", " | ")
            ) { doors, start, doorsLabel, startLabel, separator ->
                val doorsPart = "$doorsLabel: ${spellings(doors).first()}"
                val startPart = "$startLabel: ${spellings(start).first()}"
                listOf("$doorsPart$separator$startPart", "$startPart$separator$doorsPart").forEach { line ->
                    labelledClock(line, DOORS_LABELS) shouldBe doors
                    labelledClock(line, START_LABELS) shouldBe start
                }
            }
        }

    @Test
    fun `a date after a label is no clock`() =
        runTest {
            checkAll(DATE, Arb.element(DOORS + STARTS), Arb.element(LEADS)) { date, label, lead ->
                val dotted = "${date.dayOfMonth.twoDigits()}.${date.monthValue.twoDigits()}.${date.year}"
                labelledClock("$label$lead $dotted", DOORS_LABELS + "|" + START_LABELS).shouldBeNull()
                labelledClock("$label$lead ${date.dayOfMonth}. ${GERMAN_MONTH_NAMES[date.monthValue - 1]}", DOORS_LABELS + "|" + START_LABELS).shouldBeNull()
            }
        }

    @Test
    fun `a bare hour without Uhr, h or a meridiem is no clock`() =
        runTest {
            checkAll(Arb.int(0..23), Arb.element(DOORS + STARTS), Arb.element(LEADS)) { hour, label, lead ->
                labelledClock("$label$lead $hour", DOORS_LABELS + "|" + START_LABELS).shouldBeNull()
            }
        }

    @Test
    fun `a clock with no label before it is not read`() =
        runTest {
            checkAll(TIME, Arb.element("Tickets", "Saal", "Konzert", "Party", "Lesung", "")) { time, word ->
                spellings(time).forEach { clock -> labelledClock("$word $clock", DOORS_LABELS).shouldBeNull() }
            }
        }

    private companion object {
        val TIME: Arb<LocalTime> = Arb.int(0 until 24 * 60).map { LocalTime.of(it / 60, it % 60) }
        val DATE: Arb<LocalDate> = Arb.long(LocalDate.of(2020, 1, 1).toEpochDay()..LocalDate.of(2035, 12, 31).toEpochDay()).map(LocalDate::ofEpochDay)

        val DOORS = listOf("Einlass", "Doors", "Door", "Doors open", "Tür")
        val STARTS = listOf("Beginn", "Konzertbeginn", "Vorstellungsbeginn", "Start", "Starts", "Startzeit", "Show", "Showtime", "Beginnt")

        /** What a venue puts between the label and the clock. */
        val LEADS = listOf("", ":", " ab", " ca.", " um", ": ab ca.", " ab ca.", ": um", " from", " at")

        val LETTER_CASES: List<(String) -> String> = listOf({ it.lowercase() }, { it.uppercase() }, { it })

        fun Int.twoDigits(): String = toString().padStart(2, '0')

        /** Every spelling of [time] a venue prints: 24-hour with any separator and suffix, and 12-hour with any meridiem. */
        fun spellings(time: LocalTime): List<String> {
            val h = time.hour
            val m = time.minute.twoDigits()
            val twelve = if (h % 12 == 0) 12 else h % 12
            val pm = h >= 12
            val meridiems = if (pm) listOf("pm", " pm", "PM", " p.m.", "p.m.") else listOf("am", " am", "AM", " a.m.", "a.m.")
            val whole = time.minute == 0
            return listOf("${h.twoDigits()}:$m", "$h:$m", "$h.$m", "$h,$m", "$h:$m Uhr", "$h.$m Uhr", "$h:${m}h", "$h:$m h") +
                (if (whole) listOf("$h Uhr", "${h}h", "$h h") else emptyList()) +
                meridiems.map { "$twelve:$m$it" } +
                meridiems.map { "$twelve.$m$it" } +
                (if (whole) meridiems.map { "$twelve$it" } else emptyList())
        }
    }
}

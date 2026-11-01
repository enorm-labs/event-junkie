package de.norm.events.scraper

import de.norm.events.common.BERLIN
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Round trips through the shared date and clock readers: each pins one spelling a venue prints back to the value it was made from. */
class DateTimeParsingPropertyTest {
    @Test
    fun `a German dotted date reads back as the date`() =
        runTest {
            checkAll(DATE) { date ->
                listOf("d.M.yyyy", "dd.MM.yyyy").forEach { pattern ->
                    parseGermanDate(" ${date.format(DateTimeFormatter.ofPattern(pattern))} ") shouldBe date
                }
            }
        }

    @Test
    fun `a German dotted date with a two-digit year reads back in the 2000s`() =
        runTest {
            checkAll(DATE.filter { it.year in 2000..2099 }) { date ->
                parseGermanShortDate(date.format(DateTimeFormatter.ofPattern("dd.MM.yy"))) shouldBe date
                parseGermanShortDate(date.format(DateTimeFormatter.ofPattern("d.M.yy"))) shouldBe date
            }
        }

    @Test
    fun `an ISO stamp reads back as its date and clock`() =
        runTest {
            checkAll(DATE, TIME) { date, time ->
                parseIsoDate(date.toString()) shouldBe date
                parseIsoDate("${date}T$time") shouldBe date
                parseIsoTime("${date}T$time") shouldBe time
                parseRealDate("$date $time:00 +0200") shouldBe date
                parseBasicIsoDatePrefix("${date.format(COMPACT_DATE)}-some-act") shouldBe date
            }
        }

    @Test
    fun `an offset stamp reads as the same instant on the Berlin clock`() =
        runTest {
            checkAll(INSTANT_SECONDS, OFFSET) { seconds, offset ->
                val stamp = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(seconds), offset)
                val berlin = stamp.toString().parseOffsetToBerlin().shouldNotBeNull()
                berlin.toInstant() shouldBe stamp.toInstant()
                berlin.zone shouldBe BERLIN
            }
        }

    @Test
    fun `a 12-hour clock with its meridiem reads back as the 24-hour clock`() =
        runTest {
            checkAll(TIME, Arb.element("am", "AM", "a.m.", "a")) { time, am ->
                val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
                val meridiem = if (time.hour < 12) am else am.replace('a', 'p').replace('A', 'P')
                parseClock("$hour:${time.minute.twoDigits()}", meridiem) shouldBe time
            }
        }

    @Test
    fun `every weekday name reads back in any letter case`() =
        runTest {
            checkAll(Arb.element(DayOfWeek.entries), Arb.element(LETTER_CASES)) { day, case ->
                parseGermanWeekday(case(day.getDisplayName(TextStyle.FULL, Locale.GERMAN))) shouldBe day
                parseGermanWeekdayAbbreviation(case(day.getDisplayName(TextStyle.SHORT, Locale.GERMAN).trimEnd('.'))) shouldBe day
                parseEnglishWeekday(" ${case(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH))} ") shouldBe day
                parseEnglishWeekdayAbbreviation(case(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))) shouldBe day
            }
        }

    // Within today ± 2 years a month and day fall on a given weekday at most once, so the weekday names the year.
    @Test
    fun `a year-less date and its weekday name the year within two years of today`() =
        runTest {
            checkAll(DATE, Arb.int(-730..730)) { today, offset ->
                val date = today.plusDays(offset.toLong())
                if (MonthDay.from(date) != LEAP_DAY) {
                    inferYearForWeekday(MonthDay.from(date), date.dayOfWeek, clockAt(today)) shouldBe date
                }
            }
        }

    @Test
    fun `a year-less date without a weekday takes the nearest year`() =
        runTest {
            checkAll(DATE, Arb.int(-730..730)) { today, offset ->
                val monthDay = MonthDay.from(today.plusDays(offset.toLong()))
                if (monthDay != LEAP_DAY) {
                    val inferred = inferYearForWeekday(monthDay, null, clockAt(today))
                    MonthDay.from(inferred) shouldBe monthDay
                    (abs(inferred.toEpochDay() - today.toEpochDay()) <= HALF_A_LEAP_YEAR) shouldBe true
                }
            }
        }

    @Test
    fun `a weekday check keeps the date or moves it one month to that weekday`() =
        runTest {
            checkAll(DATE, Arb.element(DayOfWeek.entries)) { date, weekday ->
                val checked = dateCheckedAgainstWeekday(date, weekday, "property")
                if (checked != date) {
                    checked.dayOfWeek shouldBe weekday
                    checked.dayOfMonth shouldBe date.dayOfMonth
                    abs(checked.withDayOfMonth(1).until(date.withDayOfMonth(1)).toTotalMonths()) shouldBe 1L
                }
                dateCheckedAgainstWeekday(date, date.dayOfWeek, "property") shouldBe date
            }
        }

    private companion object {
        val DATE: Arb<LocalDate> = Arb.long(LocalDate.of(1990, 1, 1).toEpochDay()..LocalDate.of(2099, 12, 31).toEpochDay()).map(LocalDate::ofEpochDay)
        val TIME: Arb<LocalTime> = Arb.int(0 until 24 * 60).map { LocalTime.of(it / 60, it % 60) }
        val INSTANT_SECONDS: Arb<Long> = Arb.long(946_684_800L..4_102_444_800L)
        val OFFSET: Arb<ZoneOffset> = Arb.int(-48..56).map { ZoneOffset.ofTotalSeconds(it * 15 * 60) }
        val LEAP_DAY: MonthDay = MonthDay.of(2, 29)
        const val HALF_A_LEAP_YEAR = 183
        val LETTER_CASES: List<(String) -> String> = listOf({ it.lowercase() }, { it.uppercase() }, { it })

        fun Int.twoDigits(): String = toString().padStart(2, '0')

        fun clockAt(today: LocalDate): Clock = Clock.fixed(today.atStartOfDay(BERLIN).toInstant(), BERLIN)
    }
}

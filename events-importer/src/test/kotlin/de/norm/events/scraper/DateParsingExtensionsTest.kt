package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.MonthDay
import java.time.ZoneOffset

class DateParsingExtensionsTest {
    // --- parseTime ---

    @Test
    fun `parseTime parses valid HH-mm time`() {
        parseTime("19:00") shouldBe LocalTime.of(19, 0)
        parseTime("20:30") shouldBe LocalTime.of(20, 30)
        parseTime("00:00") shouldBe LocalTime.of(0, 0)
        parseTime("23:59") shouldBe LocalTime.of(23, 59)
    }

    @Test
    fun `parseTime trims whitespace`() {
        parseTime("  19:00  ") shouldBe LocalTime.of(19, 0)
        parseTime("\t20:30\n") shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `parseTime returns null for null input`() {
        parseTime(null).shouldBeNull()
    }

    @Test
    fun `parseTime returns null for blank input`() {
        parseTime("").shouldBeNull()
        parseTime("   ").shouldBeNull()
    }

    @Test
    fun `parseTime returns null for invalid format`() {
        parseTime("TBA").shouldBeNull()
        parseTime("19h00").shouldBeNull()
        parseTime("7pm").shouldBeNull()
        parseTime("25:00").shouldBeNull()
    }

    // --- parseIsoDate ---

    @Test
    fun `parseIsoDate parses full ISO datetime`() {
        parseIsoDate("2026-05-16T20:00") shouldBe LocalDate.of(2026, 5, 16)
    }

    @Test
    fun `parseIsoDate parses date-only string`() {
        parseIsoDate("2026-05-16") shouldBe LocalDate.of(2026, 5, 16)
    }

    @Test
    fun `parseIsoDate returns null for invalid input`() {
        parseIsoDate("invalid").shouldBeNull()
        parseIsoDate("16-05-2026").shouldBeNull()
        parseIsoDate("2026/05/16").shouldBeNull()
    }

    // --- parseIsoTime ---

    @Test
    fun `parseIsoTime parses time from full ISO datetime`() {
        parseIsoTime("2026-05-16T20:00") shouldBe LocalTime.of(20, 0)
        parseIsoTime("2026-05-16T19:30") shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `parseIsoTime returns null when no time component`() {
        parseIsoTime("2026-05-16").shouldBeNull()
    }

    @Test
    fun `parseIsoTime returns null for invalid time component`() {
        parseIsoTime("2026-05-16Tinvalid").shouldBeNull()
    }

    // --- parseShortDate ---

    @Test
    fun `parseShortDate parses DD-MM-YY format`() {
        parseShortDate("21/09/26") shouldBe LocalDate.of(2026, 9, 21)
    }

    @Test
    fun `parseShortDate parses single-digit day and month`() {
        parseShortDate("1/9/26") shouldBe LocalDate.of(2026, 9, 1)
    }

    @Test
    fun `parseShortDate trims whitespace`() {
        parseShortDate("  21/09/26  ") shouldBe LocalDate.of(2026, 9, 21)
    }

    @Test
    fun `parseShortDate returns null for null input`() {
        parseShortDate(null).shouldBeNull()
    }

    @Test
    fun `parseShortDate returns null for blank input`() {
        parseShortDate("").shouldBeNull()
        parseShortDate("   ").shouldBeNull()
    }

    @Test
    fun `parseShortDate returns null for invalid format`() {
        parseShortDate("invalid").shouldBeNull()
        parseShortDate("2026-09-21").shouldBeNull()
    }

    // --- parseGermanDate ---

    @Test
    fun `parseGermanDate parses DD-MM-YYYY format`() {
        parseGermanDate("10.07.2026") shouldBe LocalDate.of(2026, 7, 10)
        parseGermanDate("23.09.2026") shouldBe LocalDate.of(2026, 9, 23)
    }

    @Test
    fun `parseGermanDate parses single-digit day and month`() {
        parseGermanDate("1.9.2026") shouldBe LocalDate.of(2026, 9, 1)
    }

    @Test
    fun `parseGermanDate trims whitespace`() {
        parseGermanDate("  10.07.2026  ") shouldBe LocalDate.of(2026, 7, 10)
    }

    @Test
    fun `parseGermanDate returns null for null or blank input`() {
        parseGermanDate(null).shouldBeNull()
        parseGermanDate("").shouldBeNull()
        parseGermanDate("   ").shouldBeNull()
    }

    @Test
    fun `parseGermanDate returns null for invalid or two-digit-year format`() {
        parseGermanDate("invalid").shouldBeNull()
        parseGermanDate("2026-07-10").shouldBeNull()
        // A two-digit year must not silently parse to year 0026 — the four-digit formatter rejects it.
        parseGermanDate("11.12.26").shouldBeNull()
    }

    // --- parseGermanShortDate ---

    @Test
    fun `parseGermanShortDate parses DD-MM-YY format`() {
        parseGermanShortDate("11.12.26") shouldBe LocalDate.of(2026, 12, 11)
        parseGermanShortDate("29.06.26") shouldBe LocalDate.of(2026, 6, 29)
    }

    @Test
    fun `parseGermanShortDate parses single-digit day and month`() {
        parseGermanShortDate("1.9.26") shouldBe LocalDate.of(2026, 9, 1)
    }

    @Test
    fun `parseGermanShortDate resolves two-digit year to 2000-2099`() {
        parseGermanShortDate("01.01.00") shouldBe LocalDate.of(2000, 1, 1)
        parseGermanShortDate("31.12.99") shouldBe LocalDate.of(2099, 12, 31)
    }

    @Test
    fun `parseGermanShortDate returns null for null or blank input`() {
        parseGermanShortDate(null).shouldBeNull()
        parseGermanShortDate("").shouldBeNull()
        parseGermanShortDate("   ").shouldBeNull()
    }

    @Test
    fun `parseGermanShortDate returns null for invalid format`() {
        parseGermanShortDate("invalid").shouldBeNull()
        parseGermanShortDate("11.12.2026").shouldBeNull()
    }

    // --- parseRealDate ---

    @Test
    fun `parseRealDate reads the leading ISO date from a data-realdate attribute`() {
        parseRealDate("2026-07-08 19:00:00 +0200") shouldBe LocalDate.of(2026, 7, 8)
    }

    @Test
    fun `parseRealDate returns null for an absent or unparseable attribute`() {
        parseRealDate(null).shouldBeNull()
        parseRealDate("").shouldBeNull()
        parseRealDate("08.07.2026 19:00").shouldBeNull()
    }

    // --- parseGermanMonthAbbreviation ---

    @Test
    fun `parseGermanMonthAbbreviation maps abbreviations case- and punctuation-insensitively`() {
        parseGermanMonthAbbreviation("Okt") shouldBe Month.OCTOBER
        parseGermanMonthAbbreviation("Aug.") shouldBe Month.AUGUST
        parseGermanMonthAbbreviation("dez") shouldBe Month.DECEMBER
    }

    // German abbreviates every month to three letters except March, which venues render
    // unabbreviated in an otherwise abbreviated column, and spell four different ways.
    @Test
    fun `parseGermanMonthAbbreviation accepts every March spelling`() {
        listOf("Mrz", "mrz.", "Mär", "März", "maer", "maerz").forEach {
            parseGermanMonthAbbreviation(it) shouldBe Month.MARCH
        }
    }

    @Test
    fun `parseGermanMonthAbbreviation returns null for a spelling these sites do not render`() {
        parseGermanMonthAbbreviation("Sept").shouldBeNull()
        parseGermanMonthAbbreviation(null).shouldBeNull()
    }

    // --- inferYearForWeekday ---

    private fun clockAt(date: String) = Clock.fixed(LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    // Retro venues leave recently-passed events on the page, which is what the naive
    // "roll to next year if already past" rule gets wrong.
    @Test
    fun `inferYearForWeekday resolves a just-passed date to this year rather than a future repeat`() {
        // 3 July 2026 was a Friday, and today is the Thursday six days later.
        inferYearForWeekday(MonthDay.of(7, 3), DayOfWeek.FRIDAY, clockAt("2026-07-09")) shouldBe LocalDate.of(2026, 7, 3)
    }

    @Test
    fun `inferYearForWeekday picks the candidate year whose date lands on the stated weekday`() {
        // 3 July is a Saturday in 2027 and a Friday in 2026, so the weekday decides the year.
        inferYearForWeekday(MonthDay.of(7, 3), DayOfWeek.SATURDAY, clockAt("2026-07-09")) shouldBe LocalDate.of(2027, 7, 3)
    }

    @Test
    fun `inferYearForWeekday falls back to the nearest occurrence when the weekday is null`() {
        inferYearForWeekday(MonthDay.of(12, 31), null, clockAt("2026-01-02")) shouldBe LocalDate.of(2025, 12, 31)
        inferYearForWeekday(MonthDay.of(1, 2), null, clockAt("2026-12-30")) shouldBe LocalDate.of(2027, 1, 2)
    }

    @Test
    fun `inferYearForWeekday ignores an impossible weekday instead of returning nothing`() {
        val resolved = inferYearForWeekday(MonthDay.of(7, 3), DayOfWeek.FRIDAY, clockAt("2026-07-09"), yearWindow = 0)
        resolved shouldBe LocalDate.of(2026, 7, 3)
    }
}

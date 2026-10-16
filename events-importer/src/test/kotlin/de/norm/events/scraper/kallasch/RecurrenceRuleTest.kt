package de.norm.events.scraper.kallasch

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Unit tests for [recurrenceDates]. */
class RecurrenceRuleTest {
    private val start = LocalDate.of(2026, 10, 29)
    private val limit = LocalDate.of(2027, 1, 31)

    @Test
    fun `a monthly rule on the last Thursday`() {
        recurrenceDates("FREQ=MONTHLY;WKST=MO;BYDAY=-1TH", start, limit) shouldContainExactly
            listOf(start, LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 28))
    }

    @Test
    fun `a fifth weekday skips the months without one`() {
        recurrenceDates("FREQ=MONTHLY;BYDAY=5TH", start, limit) shouldContainExactly listOf(start, LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `a fortnightly weekly rule on two days, capped by its count`() {
        recurrenceDates("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH,SA;COUNT=3", start, limit) shouldContainExactly
            listOf(start, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 12))
    }

    @Test
    fun `a rule ends at its until date`() {
        recurrenceDates("FREQ=DAILY;UNTIL=20261031T235959Z", start, limit) shouldContainExactly
            listOf(start, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31))
    }

    @Test
    fun `a monthly rule on a day of the month, and a yearly rule`() {
        recurrenceDates("FREQ=MONTHLY;BYMONTHDAY=31", start, limit) shouldContainExactly
            listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31))
        recurrenceDates("FREQ=YEARLY", start, LocalDate.of(2028, 1, 1)) shouldContainExactly listOf(start, LocalDate.of(2027, 10, 29))
    }

    @Test
    fun `an unsupported part or frequency is null`() {
        recurrenceDates("FREQ=MONTHLY;BYSETPOS=2;BYDAY=TH", start, limit).shouldBeNull()
        recurrenceDates("FREQ=HOURLY", start, limit).shouldBeNull()
    }
}
